/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.google.cardboard.sdk.DistortionRenderer
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeTextureDescription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PanelView(context: Context, private val noLenses: Boolean, private val proofFrames: Boolean = false, private val linkFormat: String = HeadsetLink.NATIVE) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val fittedCorrection = FittedLensCorrection.enabled(context, noLenses)
    private val frameLock = Any()
    internal var preferNewestFrames = true
    internal var proofGlDelayMs = 0L
    private var proofDelayInjected = false
    private val droppedFrames = java.util.concurrent.atomic.AtomicLong()
    private var pending: Bitmap? = null
    private var pendingReceivedNs = 0L
    private var pendingTimingId = 0L
    private val timingSequence = java.util.concurrent.atomic.AtomicLong()
    var onFrameSubmitted: ((Long, Long) -> Unit)? = null
    var onFormatRejected: (() -> Unit)? = null
    private var rejectedSize = false
    private var decoderTexture = 0
    private var decoderProgram = 0
    private var decoderFramebuffer = 0
    private var decoderImage: android.graphics.SurfaceTexture? = null
    private var decoderOutput: android.view.Surface? = null
    private val decoderReady = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var decoderDone: (() -> Unit)? = null
    @Volatile private var decoderReceived = 0L
    private val planarTextures = IntArray(3)
    private var planarProgram = 0
    @Volatile private var pendingPlanes: List<ByteBuffer>? = null
    private var decoderFrames = 0
    private var decoderAvailable = 0
    private var decoderWidth = 0
    private var decoderHeight = 0
    private val topBottom = linkFormat == HeadsetLink.HALF_TB_720 || linkFormat == HeadsetLink.TB_720
    private var frameNormalized = false
    private var fitTexture = 0
    private var fitFramebuffer = 0
    private var fitProgram = 0
    private var texture = 0
    private var program = 0
    @Volatile private var panelWidth = 0
    @Volatile private var panelHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var lens: LensDistortion? = null
    private var distortion: DistortionRenderer? = null
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    init {
        Initialize.initialize(context)
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun submit(frame: Bitmap) {
        val receivedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val timingId = timingSequence.incrementAndGet()
        HeadsetTiming.record("copy_begin", timingId)
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
        HeadsetTiming.record("copy_end", timingId)
        synchronized(frameLock) {
            if (pending != null) {
                droppedFrames.incrementAndGet()
                HeadsetTiming.record("gl_replaced", pendingTimingId)
            }
            pending?.recycle()
            pendingTimingId = timingId
            pendingReceivedNs = receivedNs
            pending = copy
        }
        requestRender()
    }


    fun nativeSizeMatches(width: Int, height: Int) = width == panelWidth && height == panelHeight

    fun decoderSurface(width: Int, height: Int): android.view.Surface? {
        val ready = java.util.concurrent.CountDownLatch(1)
        var result: android.view.Surface? = null
        queueEvent {
            if (width == panelWidth && height == panelHeight) {
                decoderWidth = width; decoderHeight = height
                if (decoderOutput == null) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0); decoderTexture = ids[0]
                    GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexture)
                    GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    decoderImage = android.graphics.SurfaceTexture(decoderTexture).apply {
                        setDefaultBufferSize(width, height)
                        setOnFrameAvailableListener {
                            if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && ++decoderAvailable <= 3)
                                android.util.Log.d("HeadsetVideo", "Surface frame available ${System.nanoTime()}")
                            decoderReady.set(true); requestRender()
                        }
                    }
                    decoderOutput = android.view.Surface(decoderImage)
                    GLES20.glGenFramebuffers(1, ids, 0); decoderFramebuffer = ids[0]
                    decoderProgram = GLES20.glCreateProgram()
                    for ((type, source) in listOf(
                        GLES20.GL_VERTEX_SHADER to "attribute vec2 pos; attribute vec2 uv; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=uv;}",
                        GLES20.GL_FRAGMENT_SHADER to "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 coord; uniform samplerExternalOES frame; uniform mat4 transform; void main(){gl_FragColor=texture2D(frame,(transform*vec4(coord,0.,1.)).xy);}")) {
                        val shader = GLES20.glCreateShader(type)
                        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                        GLES20.glAttachShader(decoderProgram, shader); GLES20.glDeleteShader(shader)
                    }
                    GLES20.glLinkProgram(decoderProgram)
                }
                result = decoderOutput
            }
            ready.countDown()
        }
        val available = ready.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!available || result == null) android.util.Log.w("HeadsetVideo",
            "Decoder surface ready=$available requested=${width}x$height panel=${panelWidth}x$panelHeight")
        return if (available) result else null
    }

    fun expectDecoderFrame(received: Long, done: () -> Unit) {
        decoderReceived = received
        decoderDone = done
    }

    fun stopDecoder() { decoderDone = null; decoderReady.set(false); pendingPlanes = null }

    fun recordDecoderPlanes(sequence: Int, planes: List<ByteBuffer>) {
        if (!org.kde.kdeconnect_tp.BuildConfig.DEBUG || !proofFrames || sequence != 60) return
        java.io.File(context.filesDir, "hevc-planes-60.yuv").outputStream().use { output ->
            for (plane in planes) {
                val copy = plane.duplicate()
                val bytes = ByteArray(copy.remaining())
                copy.get(bytes)
                output.write(bytes)
            }
        }
    }

    fun submitDecoderPlanes(width: Int, height: Int, planes: List<ByteBuffer>) {
        decoderWidth = width; decoderHeight = height
        pendingPlanes = planes
        requestRender()
    }

    private fun uploadPlanarFrame(planes: List<ByteBuffer>) {
        if (planarProgram == 0) {
            GLES20.glGenTextures(3, planarTextures, 0)
            if (decoderFramebuffer == 0) {
                val ids = IntArray(1)
                GLES20.glGenFramebuffers(1, ids, 0); decoderFramebuffer = ids[0]
            }
            planarProgram = GLES20.glCreateProgram()
            // Android ColorConverter BT709_FULL coefficients, evaluated at native samples.
            val fragment = """precision highp float; varying vec2 coord;
                uniform sampler2D planeY; uniform sampler2D planeU; uniform sampler2D planeV;
                void main() {
                    vec2 uv = vec2(coord.x, 1.0-coord.y);
                    vec3 yuv = floor(vec3(texture2D(planeY,uv).r, texture2D(planeU,uv).r, texture2D(planeV,uv).r)*255.0+0.5);
                    float base = yuv.x*256.0+128.0; float u=yuv.y-128.0; float v=yuv.z-128.0;
                    vec3 rgb = floor(vec3(base+403.0*v,base-48.0*u-120.0*v,base+475.0*u)/256.0);
                    gl_FragColor = vec4(clamp(rgb,0.0,255.0)/255.0,1.0);
                }"""
            for ((type, source) in listOf(GLES20.GL_VERTEX_SHADER to
                "attribute vec2 pos; attribute vec2 uv; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=uv;}",
                GLES20.GL_FRAGMENT_SHADER to fragment)) {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                GLES20.glAttachShader(planarProgram, shader); GLES20.glDeleteShader(shader)
            }
            GLES20.glLinkProgram(planarProgram)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, decoderWidth, decoderHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, decoderFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        GLES20.glViewport(0, 0, decoderWidth, decoderHeight)
        GLES20.glUseProgram(planarProgram)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        for (i in 0..2) {
            val factor = if (i == 0) 1 else 2
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, planarTextures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, (decoderWidth+factor-1)/factor,
                (decoderHeight+factor-1)/factor, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[i])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(planarProgram, listOf("planeY","planeU","planeV")[i]), i)
        }
        val pos = GLES20.glGetAttribLocation(planarProgram, "pos")
        val uv = GLES20.glGetAttribLocation(planarProgram, "uv")
        vertices.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        frameNormalized = false
        frameWidth = decoderWidth; frameHeight = decoderHeight
    }

    private fun uploadDecoderFrame(): Boolean {
        pendingPlanes?.let {
            pendingPlanes = null
            uploadPlanarFrame(it)
            return true
        }
        if (!decoderReady.getAndSet(false)) return false
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && decoderFrames < 3)
            android.util.Log.d("HeadsetVideo", "GL upload starts ${System.nanoTime()}")
        decoderImage?.updateTexImage()
        val transform = FloatArray(16)
        decoderImage?.getTransformMatrix(transform)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, decoderWidth, decoderHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, decoderFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        GLES20.glViewport(0, 0, decoderWidth, decoderHeight)
        GLES20.glUseProgram(decoderProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(decoderProgram, "frame"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(decoderProgram, "transform"), 1, false, transform, 0)
        val pos = GLES20.glGetAttribLocation(decoderProgram, "pos")
        val uv = GLES20.glGetAttribLocation(decoderProgram, "uv")
        vertices.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        frameNormalized = false
        frameWidth = decoderWidth; frameHeight = decoderHeight
        return true
    }

    private fun fitFrame(): Boolean {
        if (frameNormalized) return true
        val fit = when (linkFormat) {
            HeadsetLink.TB_720 ->
                if (frameHeight % 2 == 0) PanelFrameFit.fit(frameWidth * 2, frameHeight / 2, panelWidth, panelHeight) else null
            HeadsetLink.HALF_TB_720, HeadsetLink.HALF_SBS_720 ->
                if (frameWidth % 2 == 0 && frameHeight % 2 == 0) PanelFrameFit.fit(frameWidth * 2, frameHeight, panelWidth, panelHeight) else null
            else -> PanelFrameFit.fit(frameWidth, frameHeight, panelWidth, panelHeight)
        }
        if (fit == null) return false
        if (!topBottom && linkFormat != HeadsetLink.HALF_SBS_720 && frameWidth == panelWidth && frameHeight == panelHeight) return true
        if (fitProgram == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0); fitTexture = ids[0]
            GLES20.glGenFramebuffers(1, ids, 0); fitFramebuffer = ids[0]
            fitProgram = GLES20.glCreateProgram()
            for ((type, source) in listOf(
                GLES20.GL_VERTEX_SHADER to "attribute vec2 pos; attribute vec2 uv; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=uv;}",
                GLES20.GL_FRAGMENT_SHADER to "precision highp float; varying vec2 coord; uniform sampler2D frame; uniform float eye; uniform float topBottom; void main(){vec2 uv=vec2(coord.x,1.0-coord.y);gl_FragColor=texture2D(frame,mix(vec2((uv.x+eye)*0.5,uv.y),vec2(uv.x,(uv.y+eye)*0.5),topBottom));}")) {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                GLES20.glAttachShader(fitProgram, shader); GLES20.glDeleteShader(shader)
            }
            GLES20.glLinkProgram(fitProgram)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fitTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, panelWidth, panelHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fitFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fitTexture, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(fitProgram)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(fitProgram, "frame"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(fitProgram, "topBottom"), if (topBottom) 1f else 0f)
        val position = GLES20.glGetAttribLocation(fitProgram, "pos")
        val uv = GLES20.glGetAttribLocation(fitProgram, "uv")
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        for (eye in 0..1) {
            GLES20.glViewport(eye * panelWidth / 2 + (panelWidth / 2 - fit.width) / 2,
                (panelHeight - fit.height) / 2, fit.width, fit.height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(fitProgram, "eye"), eye.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        val input = texture
        texture = fitTexture
        fitTexture = input
        frameWidth = panelWidth; frameHeight = panelHeight
        frameNormalized = true
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, panelWidth, panelHeight)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        return true
    }

    private fun decoderSubmitted() {
        GLES20.glFinish()
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            android.util.Log.w("HeadsetVideo", "GL frame rejected: $error")
            decoderDone = null
            return
        }
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && ++decoderFrames <= 3)
            android.util.Log.d("HeadsetVideo", "GL submitted ${System.nanoTime()} error=$error")
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && proofFrames && decoderFrames == 60) {
            val pixels = ByteBuffer.allocateDirect(panelWidth * panelHeight * 4)
            GLES20.glReadPixels(0, 0, panelWidth, panelHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val bytes = ByteArray(pixels.capacity())
            pixels.get(bytes)
            java.io.File(context.filesDir, "hevc-panel-60.rgba").writeBytes(bytes)
            android.util.Log.d("HeadsetVideo", "Synthetic proof frame 60 saved ${System.nanoTime()}")
        }
        decoderDone?.also { decoderDone = null; it() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        decoderOutput?.release(); decoderImage?.release()
        decoderOutput = null; decoderImage = null
        decoderTexture = 0; decoderProgram = 0; decoderFramebuffer = 0
        planarProgram = 0; planarTextures.fill(0)
        fitProgram = 0; fitTexture = 0; fitFramebuffer = 0
        stopDecoder()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = GLES20.glCreateProgram()
        for ((type, source) in listOf(
            GLES20.GL_VERTEX_SHADER to "attribute vec2 pos; attribute vec2 uv; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=uv;}",
            GLES20.GL_FRAGMENT_SHADER to FittedLensCorrection.fragment(context))) {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            GLES20.glAttachShader(program, shader)
            GLES20.glDeleteShader(shader)
        }
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        frameNormalized = false
        frameWidth = 0
        frameHeight = 0
        lens = null
        distortion = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG) android.util.Log.d("HeadsetPanel", "Surface ${width}x$height, no lenses=$noLenses")
        panelWidth = width
        panelHeight = height
        distortion?.close()
        lens?.close()
        if (!noLenses) {
            lens = LensDistortion(QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams(), width, height)
            distortion = DistortionRenderer().apply {
                setMesh(lens!!.getDistortionMesh(0), 0)
                setMesh(lens!!.getDistortionMesh(1), 1)
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val decoded = uploadDecoderFrame()
        var receivedNs = if (decoded) decoderReceived else 0L
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, panelWidth, panelHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        var timingId = 0L
        var frame = synchronized(frameLock) {
            timingId = pendingTimingId
            if (decoded) { pending?.recycle(); pending = null }
            if (!decoded) receivedNs = pendingReceivedNs
            pending.also { pending = null }
        }
        if (frame != null && org.kde.kdeconnect_tp.BuildConfig.DEBUG && !proofDelayInjected
            && proofGlDelayMs > 0 && timingId >= 20) {
            proofDelayInjected = true
            Thread.sleep(proofGlDelayMs.coerceAtMost(2000))
        }
        if (frame != null && preferNewestFrames && android.os.SystemClock.elapsedRealtimeNanos() - receivedNs > 100_000_000L) {
            frame?.recycle()
            frame = null
            droppedFrames.incrementAndGet()
            HeadsetTiming.record("gl_dropped_late", timingId)
            android.util.Log.i("HeadsetNetwork", "decodedFramesDropped=${droppedFrames.get()} queueDepth=0")
            receivedNs = 0
        }
        frame?.let {
            HeadsetTiming.record("gl_upload_begin", timingId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            frameNormalized = false
            frameWidth = it.width
            frameHeight = it.height
            HeadsetTiming.record("gl_upload_end", timingId)
            it.recycle()
        }
        // Stream mode changes do not change the physical panel or its lens profile.
        if (!fitFrame()) {
            if (frameWidth > 0 && !rejectedSize) {
                rejectedSize = true
                android.util.Log.w("HeadsetPanel", "Frame ${frameWidth}x$frameHeight does not match panel ${panelWidth}x$panelHeight")
                post { onFormatRejected?.invoke() }
            }
            return
        }
        if (!noLenses) {
            fun eye(left: Float, right: Float) = EyeTextureDescription().apply {
                this.texture = this@PanelView.texture.toLong()
                leftU = left
                rightU = right
                topV = 0f
                bottomV = 1f
            }
            distortion?.renderEyeToDisplay(0, 0, 0, panelWidth, panelHeight, eye(0f, .5f), eye(.5f, 1f))
            if (frame != null) HeadsetTiming.record("gl_submit", timingId)
            if (frame != null || decoded) onFrameSubmitted?.invoke(receivedNs, android.os.SystemClock.elapsedRealtimeNanos())
            if (decoded) decoderSubmitted()
            return
        }
        GLES20.glUseProgram(program)
        FittedLensCorrection.bind(program, panelWidth, panelHeight, false, fittedCorrection)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0)
        val position = GLES20.glGetAttribLocation(program, "pos")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (frame != null) HeadsetTiming.record("gl_submit", timingId)
        if (frame != null || decoded) onFrameSubmitted?.invoke(receivedNs, android.os.SystemClock.elapsedRealtimeNanos())
        if (decoded) decoderSubmitted()
    }

    override fun onPause() {
        queueEvent {
            distortion?.close()
            lens?.close()
            distortion = null
            lens = null
        }
        super.onPause()
    }

    fun release() {
        synchronized(frameLock) { pending?.recycle(); pending = null }
        queueEvent {
            distortion?.close()
            lens?.close()
            distortion = null
            lens = null
            decoderOutput?.release(); decoderImage?.release()
            decoderOutput = null; decoderImage = null
            GLES20.glDeleteTextures(1, intArrayOf(decoderTexture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(decoderFramebuffer), 0)
            GLES20.glDeleteProgram(decoderProgram)
            GLES20.glDeleteProgram(fitProgram)
            GLES20.glDeleteTextures(1, intArrayOf(fitTexture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fitFramebuffer), 0)
            GLES20.glDeleteProgram(planarProgram)
            GLES20.glDeleteTextures(3, planarTextures, 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
        }
    }
}
