/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Live access units on the existing authenticated RDP connection. No file or second socket. */
class HeadsetVideo(private val panel: PanelView, private val enabled: () -> Boolean) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var software = false
    private var planeBuffers = emptyList<ByteBuffer>()
    private var width = 0
    private var height = 0
    private var rate = 0
    private var sequence = 0
    @Volatile var active = false
        private set

    @Synchronized fun receive(packet: ByteArray): Int {
        if (packet.isEmpty()) { close(); return 0 }
        try {
            require(packet.size in 36..(8 * 1024 * 1024 + 36))
            val input = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            require(input.int == 1)
            val kind = input.int
            val w = input.int; val h = input.int; val hz = input.int
            val seq = input.int; val flags = input.int; val size = input.int
            require(input.int == 2 && size == packet.size - 36)
            if (kind == 1) {
                require(enabled() && codec == null && seq == 0 && size == 0)
                require(w > 0 && h > 0 && hz in 1000..240000)
                val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                        info.getCapabilitiesForType("video/hevc").let { caps ->
                            caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain } &&
                                caps.videoCapabilities?.areSizeAndRateSupported(w, h, hz / 1000.0) == true
                        }
                } ?: error("No HEVC Main decoder for ${w}x$h at $hz mHz")
                software = HeadsetCodec.isSoftware(selected.name,
                    Build.VERSION.SDK_INT >= 29 && selected.isSoftwareOnly)
                val caps = selected.getCapabilitiesForType("video/hevc")
                val format = MediaFormat.createVideoFormat("video/hevc", w, h).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                    if (software) setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setFloat(MediaFormat.KEY_FRAME_RATE, hz / 1000f)
                    if (Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                require(panel.nativeSizeMatches(w, h)) { "Offer does not match the native panel" }
                val surface = if (software) null else panel.decoderSurface(w, h) ?: error("Native panel surface unavailable")
                planeBuffers = if (software) listOf(ByteBuffer.allocateDirect(w * h),
                    ByteBuffer.allocateDirect((w + 1) / 2 * ((h + 1) / 2)),
                    ByteBuffer.allocateDirect((w + 1) / 2 * ((h + 1) / 2))) else emptyList()
                codec = MediaCodec.createByCodecName(selected.name)
                codec!!.configure(format, surface, null, 0)
                codec!!.start()
                width = w; height = h; rate = hz
                Log.i("HeadsetVideo", "HEVC ready ${selected.name} ${w}x$h at $hz mHz software=$software")
                return 1
            }
            require(kind == 3 && w == width && h == height && hz == rate && seq == sequence + 1)
            require(sequence > 0 || flags and 1 != 0)
            val decoder = codec ?: error("HEVC not configured")
            val index = decoder.dequeueInputBuffer(100000)
            require(index >= 0) { "Input buffer timeout" }
            val buffer = decoder.getInputBuffer(index)!!
            require(size <= buffer.capacity()) { "Access unit exceeds input buffer" }
            buffer.clear(); buffer.put(packet, 36, size)
            val received = android.os.SystemClock.elapsedRealtimeNanos()
            decoder.queueInputBuffer(index, 0, size, seq.toLong() * 1_000_000_000L / rate, 0)
            val info = MediaCodec.BufferInfo()
            val waitMs = if (sequence == 0) 2000L else 500L
            val deadline = System.nanoTime() + waitMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val output = decoder.dequeueOutputBuffer(info, 10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = decoder.outputFormat
                    Log.i("HeadsetVideo", "HEVC output format $outputFormat")
                    fun cropSize(first: String, last: String, dimension: String): Int =
                        if (outputFormat.containsKey(first) && outputFormat.containsKey(last))
                            outputFormat.getInteger(last) - outputFormat.getInteger(first) + 1
                        else outputFormat.getInteger(dimension)
                    require(cropSize("crop-left", "crop-right", MediaFormat.KEY_WIDTH) == width &&
                        cropSize("crop-top", "crop-bottom", MediaFormat.KEY_HEIGHT) == height) { "Decoder changed visible geometry" }
                } else if (output >= 0) {
                    val presented = CountDownLatch(1)
                    panel.expectDecoderFrame(received) { presented.countDown() }
                    if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && seq <= 3)
                        Log.d("HeadsetVideo", "Release decoded frame $seq ${System.nanoTime()}")
                    if (software) {
                        val format = decoder.outputFormat
                        require(format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709 &&
                            format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL) { "Unsupported software color space" }
                        decoder.getOutputImage(output)?.use { image ->
                            val crop = image.cropRect
                            require(crop.width() == width && crop.height() == height && crop.left % 2 == 0 && crop.top % 2 == 0)
                            for (i in 0..2) {
                                val factor = if (i == 0) 1 else 2
                                val plane = image.planes[i]
                                HeadsetPlanes.pack(plane.buffer, plane.rowStride, plane.pixelStride,
                                    crop.left / factor, crop.top / factor, (width + factor - 1) / factor,
                                    (height + factor - 1) / factor, planeBuffers[i])
                            }
                        } ?: error("Software decoder did not expose YUV planes")
                        panel.recordDecoderPlanes(seq, planeBuffers)
                        panel.submitDecoderPlanes(width, height, planeBuffers)
                        decoder.releaseOutputBuffer(output, false)
                    } else {
                        decoder.releaseOutputBuffer(output, true)
                    }
                    require(presented.await(waitMs, TimeUnit.MILLISECONDS)) { "GL submission timeout" }
                    sequence = seq
                    active = true
                    if (seq <= 5 || seq % 300 == 0) Log.i("HeadsetVideo", "HEVC frame $seq decoded and GL submitted at ${System.nanoTime()}")
                    return 1
                }
            }
            error("HEVC decoder output timeout")
        } catch (error: Exception) {
            Log.w("HeadsetVideo", "H.264 fallback: ${error.message}")
            close()
            return 0
        }
    }

    @Synchronized override fun close() {
        active = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null; sequence = 0
        planeBuffers = emptyList()
        panel.stopDecoder()
    }
}
