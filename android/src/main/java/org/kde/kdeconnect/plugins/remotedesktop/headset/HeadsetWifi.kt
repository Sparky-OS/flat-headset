/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/** Reads link properties only. Never reads SSID, BSSID, MAC, scan results or location. */
internal class HeadsetWifi(context: Context) : AutoCloseable {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.WifiLock? = null

    fun setActive(active: Boolean) {
        if (!active) { close(); return }
        if (Build.VERSION.SDK_INT < 29 || lock != null) return
        try {
            lock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "KDEConnect:Throw").also {
                it?.setReferenceCounted(false)
                it?.acquire()
            }
            Log.i("HeadsetNetwork", "wifiLowLatency requested=${lock?.isHeld == true}")
        } catch (error: SecurityException) {
            lock = null
            Log.w("HeadsetNetwork", "Wi-Fi low-latency lock unavailable")
        }
    }

    override fun close() {
        lock?.let { if (it.isHeld) it.release() }
        if (lock != null) Log.i("HeadsetNetwork", "wifiLowLatency released")
        lock = null
    }

    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): HeadsetWifiSample? = try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null else {
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = (if (Build.VERSION.SDK_INT >= 31) capabilities.transportInfo as? WifiInfo else null) ?: manager?.connectionInfo
                info?.let { HeadsetWifiSample(
                    if (Build.VERSION.SDK_INT >= 30) it.wifiStandard else 0,
                    it.frequency,
                    if (Build.VERSION.SDK_INT >= 29) it.txLinkSpeedMbps else -1,
                    if (Build.VERSION.SDK_INT >= 29) it.rxLinkSpeedMbps else it.linkSpeed,
                    it.rssi,
                ) }
            }
        } catch (error: SecurityException) { null }
    }
}
