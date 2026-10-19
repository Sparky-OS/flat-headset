/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import com.google.cardboard.sdk.DistortionRenderer
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeTextureDescription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Decoder texture -> full SBS at the panel's eye size -> Cardboard display transform. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MovieView(context: Context, private val noLenses: Boolean) :
    GLSurfaceView(context), GLSurfaceView.Renderer {
    var surfaceReady: ((Surface) -> Unit)? = null
    var formatRejected: (() -> Unit)? = null
    @Volatile var format: Format? = null
    private val fittedCorrection = FittedLensCorrection.enabled(context, noLenses)
    private val newFrame = AtomicBoolean()
    private var available = false
    private var decoder: SurfaceTexture? = null
    private var surface: Surface? = null
    private var input = 0
    private var packed = 0
    private var framebuffer = 0
    private var decodeProgram = 0
    private var displayProgram = 0
    private var width = 0
    private var height = 0
    private var lens: LensDistortion? = null
    private var distortion: DistortionRenderer? = null
    private val transform = FloatArray(16)
    private val vertices =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        Initialize.initialize(context)
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun program(fragment: String): Int {
        val result = GLES20.glCreateProgram()
        for ((type, text) in
            listOf(
                GLES20.GL_VERTEX_SHADER to
                    "attribute vec2 pos; attribute vec2 uv; varying vec2 coord; void main(){gl_Position=vec4(pos,0.,1.);coord=uv;}",
                GLES20.GL_FRAGMENT_SHADER to fragment,
            )) {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, text)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            GLES20.glAttachShader(result, shader)
            GLES20.glDeleteShader(shader)
        }
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
        return result
    }

    private fun texture(target: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(target, id[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        input = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        packed = texture(GLES20.GL_TEXTURE_2D)
        decodeProgram =
            program(
                """#extension GL_OES_EGL_image_external : require
            precision highp float; uniform samplerExternalOES frame; uniform mat4 transform;
            uniform vec4 region; varying vec2 coord;
            void main(){vec2 p=region.xy+vec2(coord.x,1.-coord.y)*region.zw; gl_FragColor=texture2D(frame,(transform*vec4(p,0.,1.)).xy);}"""
            )
        displayProgram =
            program(
                FittedLensCorrection.fragment(context)
            )
        val id = IntArray(1)
        GLES20.glGenFramebuffers(1, id, 0)
        framebuffer = id[0]
        decoder =
            SurfaceTexture(input).apply {
                setOnFrameAvailableListener {
                    newFrame.set(true)
                    requestRender()
                }
            }
        surface = Surface(decoder)
        available = false
        post { surface?.let { surfaceReady?.invoke(it) } }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, packed)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            packed,
            0,
        )
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        )
        distortion?.close()
        lens?.close()
        if (!noLenses) {
            lens =
                LensDistortion(
                    QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams(),
                    width,
                    height,
                )
            distortion =
                DistortionRenderer().apply {
                    setMesh(lens!!.getDistortionMesh(0), 0)
                    setMesh(lens!!.getDistortionMesh(1), 1)
                }
        }
    }

    private fun draw(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "pos")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (newFrame.getAndSet(false)) {
            decoder?.updateTexImage()
            decoder?.getTransformMatrix(transform)
            available = true
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val f = format
        if (available && f != null && f.width > 0 && f.height > 0) {
            val mode = f.stereoMode
            val horizontal = mode == C.STEREO_MODE_LEFT_RIGHT || mode == C.STEREO_MODE_RIGHT_LEFT
            val vertical = mode == C.STEREO_MODE_TOP_BOTTOM || mode == C.STEREO_MODE_BOTTOM_TOP
            if (!horizontal && !vertical && mode != C.STEREO_MODE_MONO && mode != Format.NO_VALUE) {
                post { formatRejected?.invoke() }
                return
            }
            val aspect =
                f.width * f.pixelWidthHeightRatio / f.height *
                    (if (vertical) 2f else if (horizontal) .5f else 1f)
            val eyeWidth = width / 2
            val viewportWidth = minOf(eyeWidth, (height * aspect).toInt())
            val viewportHeight = minOf(height, (eyeWidth / aspect).toInt())
            GLES20.glUseProgram(decodeProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(decodeProgram, "frame"), 0)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(decodeProgram, "transform"),
                1,
                false,
                transform,
                0,
            )
            for (eye in 0..1) {
                val index =
                    if (mode == C.STEREO_MODE_RIGHT_LEFT || mode == C.STEREO_MODE_BOTTOM_TOP)
                        1 - eye
                    else eye
                GLES20.glViewport(
                    eye * eyeWidth + (eyeWidth - viewportWidth) / 2,
                    (height - viewportHeight) / 2,
                    viewportWidth,
                    viewportHeight,
                )
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(decodeProgram, "region"),
                    if (horizontal) index * .5f else 0f,
                    if (vertical) (1 - index) * .5f else 0f,
                    if (horizontal) .5f else 1f,
                    if (vertical) .5f else 1f,
                )
                draw(decodeProgram)
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, packed)
        if (noLenses) {
            GLES20.glUseProgram(displayProgram)
            FittedLensCorrection.bind(displayProgram, width, height, true, fittedCorrection)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(displayProgram, "frame"), 0)
            draw(displayProgram)
        } else {
            fun eye(left: Float, right: Float) =
                EyeTextureDescription().apply {
                    texture = packed.toLong()
                    leftU = left
                    rightU = right
                    bottomV = 1f
                    topV = 0f
                }
            distortion?.renderEyeToDisplay(0, 0, 0, width, height, eye(0f, .5f), eye(.5f, 1f))
        }
    }

    fun release() {
        queueEvent {
            surface?.release()
            surface = null
            decoder?.release()
            decoder = null
            distortion?.close()
            lens?.close()
            GLES20.glDeleteTextures(2, intArrayOf(input, packed), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteProgram(decodeProgram)
            GLES20.glDeleteProgram(displayProgram)
        }
    }
}
