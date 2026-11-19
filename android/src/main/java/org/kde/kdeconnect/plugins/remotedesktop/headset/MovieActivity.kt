/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.kde.kdeconnect_tp.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MovieActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var playback: MoviePlayback
    private lateinit var view: MovieView
    private var announcement: MovieAnnouncement? = null
    @Volatile private var closed = false
    @Volatile private var moviePeer: Pair<java.net.InetAddress, java.security.cert.X509Certificate>? = null
    @Volatile private var seekOffset = 0L
    private var pairingListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var pendingEvent = false
    private val notify = Runnable {
        pendingEvent = false
        if (!closed)
            worker.execute {
                announcement?.let { device ->
                    for (service in MovieServices.all) device.notify(
                        service,
                        playback.snapshot(service.id),
                    )
                }
            }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        supportActionBar?.hide()
        if (org.kde.kdeconnect.helpers.TrustedDevices.getAllTrustedDevices(this).isEmpty()) {
            setContentView(TextView(this).apply { setText(R.string.headset_pair_first); setPadding(32, 32, 32, 32) })
            return
        }
        HeadsetFitOverlay.allowFullPanel(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val panel =
            PanelDescriptor.measure(
                this,
                intent.getBooleanExtra(HeadsetActivity.EXTRA_NO_LENSES, true),
            )
        val trust = PairedTls(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this)
                .setDataSourceFactory {
                    PairedMovieDataSource(trust, { moviePeer },
                        "FlatHeadset/1.0 Panel/${panel.getInt("width")}x${panel.getInt("height")}", { seekOffset })
                })
            .build()
        val capabilities = MovieCapabilities()
        playback =
            MoviePlayback(
                player,
                {
                    ((window.attributes.screenBrightness.takeIf { it >= 0f } ?: .5f) * 100)
                        .roundToInt()
                },
                {
                    window.attributes = window.attributes.apply { screenBrightness = it / 100f }
                },
                capabilities,
                validateUri = { value ->
                    runCatching {
                        val url = java.net.URI(value)
                        val peer = moviePeer
                        peer != null && trust.isPaired(peer.second) && url.scheme == "https" &&
                            url.userInfo == null && java.net.InetAddress.getByName(url.host) == peer.first
                    }.getOrDefault(false)
                },
                timeSeek = { seekOffset = it },
            )
        playback.changed = {
            if (!pendingEvent && !closed) {
                pendingEvent = true
                handler.postDelayed(notify, 200)
            }
        }
        val status =
            TextView(this).apply {
                setText(R.string.headset_movie_waiting)
                setTextColor(android.graphics.Color.WHITE)
            }
        pairingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val peer = moviePeer
            if (org.kde.kdeconnect.helpers.TrustedDevices.getAllTrustedDevices(this).isEmpty() ||
                (peer != null && !trust.isPaired(peer.second))) {
                runOnUiThread {
                    if (!closed) {
                        closed = true
                        playback.closed = true
                        player.stop()
                        status.setText(R.string.headset_pair_first)
                        status.visibility = android.view.View.VISIBLE
                        worker.execute { announcement?.close(); announcement = null }
                    }
                }
            }
        }
        getSharedPreferences("trusted_devices", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(pairingListener)
        view =
            MovieView(this, intent.getBooleanExtra(HeadsetActivity.EXTRA_NO_LENSES, true)).apply {
                surfaceReady = { player.setVideoSurface(it) }
                formatRejected = {
                    player.stop()
                    status.setText(R.string.headset_movie_unsupported)
                    status.visibility = android.view.View.VISIBLE
                }
            }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(HeadsetFitOverlay(this@MovieActivity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(status)
            }
        )
        player.addVideoFrameMetadataListener { _, _, format, _ -> view.format = format }
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    status.visibility = android.view.View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    status.setText(R.string.headset_movie_failed)
                    status.visibility = android.view.View.VISIBLE
                    Log.w("HeadsetMovie", "Playback error code ${error.errorCode}")
                }
            }
        )
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    Log.i(
                        "HeadsetMovie",
                        "Decoder $decoderName, initialization ${initializationDurationMs}ms",
                    )
                }
            }
        )
        worker.execute {
            try {
                if (closed) return@execute
                val device = MovieAnnouncement(this, panel, playback, capabilities) { address, certificate -> moviePeer = address to certificate }
                if (closed) { device.close(); return@execute }
                announcement = device
                if (org.kde.kdeconnect_tp.BuildConfig.DEBUG)
                    Log.d(
                        "HeadsetMovie",
                        "Renderer ${announcement!!.address}, identity ${panel.getString("identity")}",
                    )
            } catch (error: Exception) {
                Log.w("HeadsetMovie", "Renderer discovery failed", error)
                runOnUiThread { if (!closed) status.setText(R.string.headset_discovery_failed) }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus)
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun dispatchTouchEvent(event: MotionEvent) = true

    override fun dispatchGenericMotionEvent(event: MotionEvent) = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val current = window.attributes.screenBrightness.takeIf { it >= 0f } ?: .5f
                window.attributes =
                    window.attributes.apply {
                        screenBrightness =
                            (current +
                                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) .05f
                                    else -.05f)
                                .coerceIn(0f, 1f)
                    }
                if (::playback.isInitialized) playback.changed?.invoke()
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP)
            finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (::view.isInitialized) view.onResume()
    }

    override fun onPause() {
        if (::player.isInitialized) player.pause()
        if (::view.isInitialized) view.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        pairingListener?.let { getSharedPreferences("trusted_devices", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        if (::playback.isInitialized) playback.closed = true
        handler.removeCallbacks(notify)
        if (::player.isInitialized) player.release()
        if (::view.isInitialized) view.release()
        worker.execute {
            announcement?.close()
            announcement = null
        }
        worker.shutdown()
        super.onDestroy()
    }
}
