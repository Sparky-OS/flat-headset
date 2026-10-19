/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

/** PHY rates are hints, never a measurement of available video bandwidth. */
internal data class HeadsetWifiSample(val standard: Int, val frequency: Int, val txMbps: Int, val rxMbps: Int, val rssi: Int) {
    val band: String get() = when (frequency) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..71000 -> "60 GHz"
        else -> "?"
    }
    val standardName: String get() = when (standard) {
        1 -> "802.11a/b/g"
        4 -> "802.11n"
        5 -> "802.11ac"
        6 -> "802.11ax"
        7 -> "802.11ad"
        8 -> "802.11be"
        else -> "802.11?"
    }
}

internal object HeadsetLinkPolicy {
    enum class Reason { UNKNOWN_LINK, PHY_HEADROOM, LIMITED_LINK, MEASURED_DELAY }
    data class Choice(val format: String, val reason: Reason)
    data class Timing(val renderP95Ms: Long, val intervalP95Ms: Long, val samples: Int)

    /** Select at connection time; geometry never changes without negotiating a new session. */
    fun choose(wifi: HeadsetWifiSample?, measured: Map<String, Timing>): Choice {
        val limited = wifi != null && (wifi.rxMbps in 1..99 || wifi.rssi in -126..-73)
        val candidates = if (limited) listOf(HeadsetLink.HALF_TB_720, HeadsetLink.HALF_SBS_720) else HeadsetLink.formats
        val qualified = measured.filter { (key, value) -> key in candidates && value.samples >= 30 && value.renderP95Ms >= 0 && value.intervalP95Ms > 0 }
        if (qualified.isNotEmpty()) {
            val winner = qualified.minBy { (_, time) -> maxOf(time.renderP95Ms, time.intervalP95Ms) }.key
            return Choice(winner, Reason.MEASURED_DELAY)
        }
        if (wifi == null || wifi.rxMbps <= 0) return Choice(HeadsetLink.NATIVE, Reason.UNKNOWN_LINK)
        if (limited) return Choice(HeadsetLink.HALF_TB_720, Reason.LIMITED_LINK)
        return Choice(if (wifi.rxMbps >= 400 && wifi.standard >= 5) HeadsetLink.NATIVE else HeadsetLink.SBS_720, Reason.PHY_HEADROOM)
    }
}
