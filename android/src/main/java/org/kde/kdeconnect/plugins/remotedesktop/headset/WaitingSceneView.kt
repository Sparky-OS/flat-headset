/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.kde.kdeconnect_tp.R

/** A wait owns its decoder. The first submitted desktop frame releases it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class WaitingSceneView(context: Context, private val noLenses: Boolean,
    private val assetDirectory: String = WaitingScenes.DIRECTORY,
    private val decoderSelector: MediaCodecSelector = MediaCodecSelector { mime, secure, tunneled ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled).filter {
            WaitingScenes.hardwareDecoder(it.name, it.hardwareAccelerated, it.softwareOnly)
        }
    }) : FrameLayout(context), AutoCloseable {
    private val settings = context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, Context.MODE_PRIVATE)
    private var player: ExoPlayer? = null
    private var movie: MovieView? = null
    private var still: PanelView? = null
    private var resumed = false
    private var waiting = false
    private var reconnecting = false
    private var closed = false
    private var scene: WaitingScenes.Scene? = null
    private val status = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        override fun onDraw(canvas: Canvas) {
            val text = context.getString(if (reconnecting) R.string.headset_throw_reconnecting else R.string.headset_throw_connecting)
            paint.textSize = height * .045f
            paint.textSize *= minOf(1f, width * .43f / paint.measureText(text))
            paint.setShadowLayer(3f, 0f, 0f, Color.BLACK)
            for (eye in 0..1) canvas.drawText(text, width * (eye * .5f + .25f), height * .8f, paint)
        }
    }

    init { isClickable = true; setBackgroundColor(Color.BLACK); visibility = GONE; addView(status, LayoutParams(-1, -1)) }

    fun setWaiting(value: Boolean, reconnect: Boolean = false) {
        if (closed || (waiting == value && (!value || reconnecting == reconnect))) return
        waiting = value
        reconnecting = reconnect
        if (value) {
            val scenes = try { context.assets.open("${assetDirectory}/catalog.json").bufferedReader().use { WaitingScenes.parse(it.readText()) } }
                catch (_: Exception) { emptyList() }
            scene = WaitingScenes.choose(scenes, settings.getString("last-waiting-scene", null))
            scene?.let { settings.edit().putString("last-waiting-scene", it.id).apply() }
        }
        update()
    }

    fun setResumed(value: Boolean) { if (resumed != value) { resumed = value; update() } }

    private fun releaseMedia() {
        val releasing = player
        player = null
        releasing?.release()
        // Drain queued GL cleanup before detach asks the render thread to exit.
        movie?.let { it.release(); it.onPause(); removeView(it) }; movie = null
        still?.let { it.release(); it.onPause(); removeView(it) }; still = null
    }

    private fun update() {
        releaseMedia()
        visibility = if (waiting && resumed && !closed) VISIBLE else GONE
        if (visibility != VISIBLE) return
        status.invalidate()
        val selected = scene ?: return
        if (settings.getBoolean(WaitingScenes.REDUCE_MOTION, false)) { showStill(selected); return }
        try {
            val clip = if (reconnecting) selected.reconnect else selected.connect
            context.assets.open("${assetDirectory}/$clip").close()
            val renderers = DefaultRenderersFactory(context).setMediaCodecSelector(decoderSelector)
            val decoder = ExoPlayer.Builder(context, renderers).build()
            player = decoder
            decoder.trackSelectionParameters = decoder.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            decoder.volume = 0f
            val view = MovieView(context, noLenses)
            movie = view
            view.surfaceReady = { if (player === decoder) decoder.setVideoSurface(it) }
            view.formatRejected = { if (player === decoder) fallback(selected) }
            addView(view, 0, LayoutParams(-1, -1))
            decoder.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                    if (player !== decoder) return
                    if (format.stereoMode != C.STEREO_MODE_LEFT_RIGHT || format.width <= 0 || format.width % 2 != 0) fallback(selected)
                    else view.format = format
                }
            })
            decoder.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { if (player === decoder) fallback(selected) }
                override fun onTracksChanged(tracks: Tracks) {
                    // Unsupported tracks need not raise a decoder error.
                    if (player === decoder && !tracks.isEmpty && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) fallback(selected)
                }
            })
            decoder.repeatMode = Player.REPEAT_MODE_ONE
            decoder.setMediaItem(MediaItem.fromUri("asset:///${assetDirectory}/$clip"))
            decoder.prepare()
            decoder.playWhenReady = true
        } catch (_: Exception) { fallback(selected) }
    }

    private fun fallback(selected: WaitingScenes.Scene) { releaseMedia(); if (waiting && resumed && !closed) showStill(selected) }

    private fun showStill(selected: WaitingScenes.Scene) {
        try {
            val asset = "${assetDirectory}/${selected.still}"
            if (!context.assets.open(asset).use { WaitingScenes.isLeftFirstPng(it) }) return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outWidth % 16 != 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16 * 1024 * 1024) return
            val bitmap = context.assets.open(asset).use { BitmapFactory.decodeStream(it) } ?: return
            still = PanelView(context, noLenses).also { view ->
                view.preferNewestFrames = false
                addView(view, 0, LayoutParams(-1, -1))
                view.submit(bitmap)
            }
            bitmap.recycle()
        } catch (_: Exception) { /* The neutral, identical per-eye status remains visible. */ }
    }

    override fun close() { closed = true; waiting = false; update() }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
