/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import org.kde.kdeconnect_tp.BuildConfig

/** Resources belong to the visible, connected display session, never the launcher. */
internal class HeadsetPerformance(private val activity: Activity, private val wifiLowLatency: Boolean = true) : AutoCloseable {
    private val wifi = HeadsetWifi(activity)
    private val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KDEConnect:HeadsetDisplay").apply {
        setReferenceCounted(false)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var resumed = false
    private var closed = false
    @Volatile internal var active = false
        private set
    private val frameLock = Any()
    private val delays = ArrayList<Long>()
    private val intervals = ArrayList<Long>()
    private var previousFrame = 0L
    private var frames = 0L
    private var lastLeaseRenewedMs = -60_000L
    private val sample = object : Runnable {
        override fun run() {
            if (!active) return
            // Renew a bounded lease, so an abandoned activity cannot hold the CPU indefinitely.
            val now = SystemClock.elapsedRealtime()
            if (now - lastLeaseRenewedMs >= 60_000) {
                wakeLock.acquire(120_000)
                lastLeaseRenewedMs = now
            }
            if (BuildConfig.DEBUG) {
                val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
                synchronized(frameLock) {
                    fun p95(values: List<Long>): Long = if (values.isEmpty()) -1 else values.sorted()[((values.size - 1) * .95).toInt()]
                    Log.d("HeadsetPerformance", "tNs=${SystemClock.elapsedRealtimeNanos()} frames=$frames handoffToSubmitP95Ns=${p95(delays)} frameIntervalP95Ns=${p95(intervals)} batteryTenthsC=$temperature thermal=$thermal sustained=${supportsSustained()}")
                    delays.clear()
                    intervals.clear()
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun setConnected(value: Boolean) { connected = value; update() }
    fun setResumed(value: Boolean) { resumed = value; update() }

    private fun supportsSustained() = Build.VERSION.SDK_INT >= 24 && power.isSustainedPerformanceModeSupported

    private fun update() {
        val next = connected && resumed && !closed
        if (next == active) return
        active = next
        wifi.setActive(next && wifiLowLatency)
        if (next) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 24 && supportsSustained()) activity.window.setSustainedPerformanceMode(next)
        handler.removeCallbacks(sample)
        if (next) {
            lastLeaseRenewedMs = -60_000
            handler.post(sample)
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            synchronized(frameLock) { delays.clear(); intervals.clear(); previousFrame = 0 }
        }
    }

    /** GL submission, not physical presentation; the renderer holds only the newest frame. */
    fun frameSubmitted(receivedNs: Long, submittedNs: Long) {
        if (!BuildConfig.DEBUG || !active || receivedNs <= 0 || submittedNs < receivedNs) return
        synchronized(frameLock) {
            frames++
            if (delays.size < 2048) delays.add(submittedNs - receivedNs)
            if (previousFrame > 0 && intervals.size < 2048) intervals.add(submittedNs - previousFrame)
            previousFrame = submittedNs
        }
    }

    override fun close() { closed = true; connected = false; update() }
}
