/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.remotedesktop.RemoteDesktopActivity
import org.kde.kdeconnect.plugins.remotedesktop.RemoteDesktopPlugin
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** A launcher for the tracked function, using the application's existing trusted devices. */
class TrackedHeadsetActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var devices: LinearLayout
    private var shown: List<Pair<String, String>>? = null
    private val refresh = object : Runnable {
        override fun run() { showDevices(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.stereodroidvr_title)
        HeadsetFunctions.enter(this, true) { finish() }
        BackgroundService.Start(this)
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            addView(TextView(this@TrackedHeadsetActivity).apply { setText(R.string.headset_pair_first) })
            addView(devices)
            addView(Button(this@TrackedHeadsetActivity).apply {
                setText(R.string.kde_connect)
                setOnClickListener { startActivity(Intent(this@TrackedHeadsetActivity, MainActivity::class.java)) }
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun showDevices() {
        val available = KdeConnect.getInstance().devices.values.filter { it.isPaired && it.isReachable }
            .map { it.deviceId to it.name }.sortedBy { it.first }
        if (available == shown) return
        shown = available
        devices.removeAllViews()
        available.forEach { (id, name) ->
            devices.addView(Button(this).apply {
                text = name
                setOnClickListener {
                    startActivity(Intent(this@TrackedHeadsetActivity, RemoteDesktopActivity::class.java)
                        .putExtra(RemoteDesktopPlugin.EXTRA_DEVICE_ID, id)
                        .putExtra(HeadsetFunctions.EXTRA_TRACKED, true))
                }
            })
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { HeadsetFunctions.forget(this); super.onDestroy() }
}
