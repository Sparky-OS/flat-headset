/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect

import android.content.Intent
import android.media.MediaCodec
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.freerdp.freerdpcore.presentation.SessionActivity
import org.kde.kdeconnect.plugins.remotedesktop.headset.HeadsetFailure
import org.kde.kdeconnect.plugins.remotedesktop.headset.HeadsetVideo
import org.kde.kdeconnect.plugins.remotedesktop.headset.PanelView
import org.kde.kdeconnect_tp.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Forces a real MediaCodec state error without a computer or physical device. Debug APK only. */
class CodecFailureProofActivity : AppCompatActivity() {
    private lateinit var receiver: HeadsetVideo
    private lateinit var panel: PanelView
    private lateinit var failure: Intent
    private var choices = false
    var resultCodeFromReceiver = 0
        private set
    var choiceMade = false
        private set

    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        panel = PanelView(this, true)
        receiver = HeadsetVideo(panel) { true }
        receiver.onFailure = { reason ->
            val message = getString(R.string.headset_hevc_failed, reason)
            failure = Intent().putExtra(SessionActivity.EXTRA_CONNECTION_ERROR, message).putExtra(HeadsetFailure.CODEC, true)
            setContentView(HeadsetFailure.view(this, message))
            android.util.Log.i("CodecFailureProof", "Real MediaCodec failure: $reason")
        }
        // An unconfigured HEVC decoder must reject dequeueInputBuffer. The receiver,
        // diagnostic formatter and user choices are the production implementations.
        val decoder = MediaCodec.createDecoderByType("video/hevc")
        fun inject(name: String, value: Any) = HeadsetVideo::class.java.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
        inject("codec", decoder); inject("width", 640); inject("height", 480); inject("rate", 60000)
        val packet = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN).apply {
            listOf(1, 3, 640, 480, 60000, 1, 1, 1, 2).forEach { putInt(it) }; put(0)
        }.array()
        resultCodeFromReceiver = receiver.receive(packet)
        check(resultCodeFromReceiver == -1 && receiver.failure != null)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !choices) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    fun showChoices() {
        choices = true
        setContentView(TextView(this).apply { text = failure.getStringExtra(SessionActivity.EXTRA_CONNECTION_ERROR) })
        HeadsetFailure.offer(this, failure) { choiceMade = true }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (choices) super.dispatchTouchEvent(event) else true
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!choices && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { showChoices(); return true }
        return if (choices) super.dispatchKeyEvent(event) else true
    }
    override fun onDestroy() { receiver.close(); panel.release(); super.onDestroy() }
}
