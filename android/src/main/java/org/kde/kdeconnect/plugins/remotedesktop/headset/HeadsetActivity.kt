/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.kde.kdeconnect_tp.R
import com.freerdp.freerdpcore.presentation.SessionActivity

class HeadsetActivity : SessionActivity() {
    private var video: HeadsetVideo? = null
    private var waitingScene: WaitingSceneView? = null
    private var userStopped = false
    private var everConnected = false
    @Volatile private var firstFrame = true
    @Volatile private var resumed = false
    private var panel: PanelView? = null
    private var networkOverlay: HeadsetNetworkOverlay? = null
    private var linkStats: HeadsetLinkStats? = null
    private var performance: HeadsetPerformance? = null
    private var displayConnected = false
    private var lastProofMarker = -1
    private var proofSamples = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeadsetFitOverlay.allowFullPanel(window)
        performance = HeadsetPerformance(this, !org.kde.kdeconnect_tp.BuildConfig.DEBUG || !intent.getBooleanExtra("headsetProofDisableWifiLock", false)).also { it.setConnected(displayConnected) }
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.alpha = 0f
        val format = intent.getStringExtra(HeadsetLink.EXTRA) ?: HeadsetLink.NATIVE
        val directWifi = intent.getBooleanExtra("headsetDirectWifi", false)
        linkStats = HeadsetLinkStats(this, format, directWifi)
        panel = PanelView(this, intent.getBooleanExtra(EXTRA_NO_LENSES, false),
            org.kde.kdeconnect_tp.BuildConfig.DEBUG && intent.getBooleanExtra("headsetProofFrames", false),
            intent.getStringExtra(HeadsetLink.EXTRA) ?: HeadsetLink.NATIVE).also {
            if (org.kde.kdeconnect_tp.BuildConfig.DEBUG) {
                it.proofGlDelayMs = intent.getLongExtra("headsetProofGlDelayMs", 0)
                it.preferNewestFrames = !intent.getBooleanExtra("headsetProofKeepLateFrames", false)
            }
            it.onFrameSubmitted = { received, submitted ->
                performance?.frameSubmitted(received, submitted)
                linkStats?.submitted(received, submitted)
                if (firstFrame) { firstFrame = false; runOnUiThread { waitingScene?.setWaiting(false) } }
            }
            it.onFormatRejected = {
                Toast.makeText(this, R.string.remote_desktop_native_panel_unavailable, Toast.LENGTH_LONG).show()
                finish()
            }
            findViewById<ViewGroup>(android.R.id.content).addView(it,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        waitingScene = WaitingSceneView(this, intent.getBooleanExtra(EXTRA_NO_LENSES, false)).also {
            findViewById<ViewGroup>(android.R.id.content).addView(it, ViewGroup.LayoutParams(-1, -1))
            it.setWaiting(true, intent.getBooleanExtra("headsetReconnecting", false))
        }
        video = HeadsetVideo(panel!!) { resumed && getSharedPreferences(HeadsetFitOverlay.PREFERENCES, MODE_PRIVATE).getBoolean("hevc", false) }
        findViewById<ViewGroup>(android.R.id.content).addView(HeadsetNetworkOverlay(this, format, directWifi).also {
            networkOverlay = it
            it.setActive(displayConnected && resumed)
        },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findViewById<ViewGroup>(android.R.id.content).addView(HeadsetFitOverlay(this),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onSessionConnectionChanged(connected: Boolean) {
        if (!connected) { video?.close(); linkStats?.save() }
        displayConnected = connected
        if (connected) everConnected = true
        if (!connected) { firstFrame = true; waitingScene?.setWaiting(true, true) }
        performance?.setConnected(connected)
        networkOverlay?.setActive(connected && resumed)
        // FreeRDP's bindSession() has just reapplied its ordinary viewer bar
        // preferences. Restore the headset policy without waiting for focus.
        if (connected) hideSystemBars()
    }

    override fun onHeadsetPacket(packet: ByteArray): Int = video?.receive(packet) ?: 0

    override fun onDesktopFrame(frame: Bitmap) {
        if (video?.active == true) return
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG && proofSamples < 20 && frame.width == 2400 && frame.height == 1080) {
            val left = frame.getPixel(32, 32)
            val right = frame.getPixel(1232, 32)
            if (kotlin.math.abs(android.graphics.Color.green(left) - 96) < 16 &&
                kotlin.math.abs(android.graphics.Color.blue(left) - 32) < 16 &&
                kotlin.math.abs(android.graphics.Color.blue(right) - 224) < 16) {
                val marker = (android.graphics.Color.red(left) + 8) / 16
                if (marker != lastProofMarker) {
                    android.util.Log.d("HeadsetLatency", "$marker ${System.nanoTime()}")
                    lastProofMarker = marker
                    proofSamples++
                }
            }
        }
        if (org.kde.kdeconnect_tp.BuildConfig.DEBUG) {
            val delay = intent.getLongExtra("headsetProofClientDelayMs", 0).coerceIn(0, 500)
            if (delay > 0) Thread.sleep(delay)
        }
        panel?.submit(frame)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = true
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val current = window.attributes.screenBrightness.takeIf { it >= 0f } ?: .5f
                setBrightness(current + if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) .05f else -.05f)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) handleBackPressed()
        return true
    }

    override fun handleBackPressed() { userStopped = true; setResult(RESULT_OK); finish() }

    override fun finish() {
        if (!userStopped && everConnected) setResult(RESULT_CANCELED)
        super.finish()
    }

    override fun showConnectionProgress(): Boolean = false

    fun setBrightness(value: Float) {
        if (!value.isFinite()) return
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(.01f, 1f) }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The inherited configuration handler also restores FreeRDP's policy.
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() { super.onResume(); resumed = true; hideSystemBars(); panel?.onResume(); waitingScene?.setResumed(true); performance?.setResumed(true); networkOverlay?.setActive(displayConnected) }
    override fun onPause() { waitingScene?.setResumed(false); networkOverlay?.setActive(false); linkStats?.save(); resumed = false; video?.close(); performance?.setResumed(false); panel?.onPause(); super.onPause() }
    override fun onDestroy() { waitingScene?.close(); waitingScene = null; video?.close(); video = null; performance?.close(); performance = null; panel?.release(); panel = null; super.onDestroy() }

    companion object { const val EXTRA_NO_LENSES = "headsetNoLenses" }
}
