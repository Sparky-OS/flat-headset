/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.kde.kdeconnect.plugins.remotedesktop.headset.PanelView
import org.kde.kdeconnect.plugins.remotedesktop.headset.WaitingSceneView
import org.kde.kdeconnect.plugins.remotedesktop.headset.WaitingScenes

/** Synthetic assets, software decoding and step keys exist only in this debug rig activity. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WaitingProofActivity : Activity() {
    private var desktop: PanelView? = null
    private lateinit var wait: WaitingSceneView
    private val handler = Handler(Looper.getMainLooper())
    private var previousReduceMotion = false
    private fun record(phase: String) {
        val field = WaitingSceneView::class.java.getDeclaredField("player").apply { isAccessible = true }
        val player = field.get(wait) as ExoPlayer?
        val children = (0 until wait.childCount).joinToString { i ->
            wait.getChildAt(i).let { "${it.javaClass.simpleName}:${it.width}x${it.height}" }
        }
        Log.i("WaitingProof", "$phase visible=${wait.visibility} children=${wait.childCount} player=${player != null} format=${player?.videoFormat} views=$children")
    }
    private val tick = object : Runnable {
        override fun run() { record("state"); handler.postDelayed(this, 1000) }
    }
    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val settings = getSharedPreferences("headset-fit", MODE_PRIVATE)
        previousReduceMotion = settings.getBoolean(WaitingScenes.REDUCE_MOTION, false)
        settings.edit().putBoolean(WaitingScenes.REDUCE_MOTION, intent.getBooleanExtra("still", false)).commit()
        val directory = "headset-waiting-proof/" + if (intent.getBooleanExtra("invalid", false)) "invalid" else "valid"
        wait = if (intent.getBooleanExtra("softwareProof", false))
            WaitingSceneView(this, true, directory, MediaCodecSelector.DEFAULT)
        else WaitingSceneView(this, true, directory)
        val root = FrameLayout(this)
        if (intent.getBooleanExtra("lifecycle", false)) {
            desktop = PanelView(this, true).also { panel ->
                panel.preferNewestFrames = false
                panel.onFrameSubmitted = { _, _ -> runOnUiThread {
                    wait.setWaiting(false)
                    record("submittedDesktop")
                } }
                root.addView(panel, FrameLayout.LayoutParams(-1, -1))
            }
        }
        root.addView(wait, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        wait.setWaiting(true, intent.getBooleanExtra("reconnect", false))
        handler.postDelayed(tick, 1000)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (desktop == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_F1 -> {
                val pixels = IntArray(1280 * 720) { i -> if (i % 1280 < 640) Color.rgb(32,96,32) else Color.rgb(32,96,224) }
                val frame = Bitmap.createBitmap(pixels, 1280, 720, Bitmap.Config.ARGB_8888)
                desktop!!.submit(frame); frame.recycle()
            }
            KeyEvent.KEYCODE_F2 -> { wait.setWaiting(true, true); record("reconnect") }
            KeyEvent.KEYCODE_F3 -> { desktop!!.requestRender(); handler.postDelayed({ record("redraw") }, 500) }
            KeyEvent.KEYCODE_F4 -> { wait.setResumed(false); record("pause") }
            KeyEvent.KEYCODE_F5 -> { wait.setResumed(true); record("resume") }
            KeyEvent.KEYCODE_F6 -> { wait.close(); record("close") }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
    override fun onResume() { super.onResume(); desktop?.onResume(); wait.setResumed(true) }
    override fun onPause() { wait.setResumed(false); desktop?.onPause(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wait.close(); desktop?.release()
        getSharedPreferences("headset-fit", MODE_PRIVATE).edit().putBoolean(WaitingScenes.REDUCE_MOTION, previousReduceMotion).commit()
        super.onDestroy()
    }
}
