/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.content.Context

/** Local rendering/cadence measurements, not a claim of synchronized glass-to-glass delay. */
internal class HeadsetLinkStats(private val context: Context, private val format: String, private val directWifi: Boolean = false) {
    private val delays = ArrayList<Long>()
    private val intervals = ArrayList<Long>()
    private var submittedCount = 0
    private var previous = 0L

    @Synchronized fun submitted(receivedNs: Long, submittedNs: Long) {
        if (receivedNs <= 0 || submittedNs < receivedNs) return
        if (delays.size >= 300) delays.removeAt(0)
        delays.add((submittedNs - receivedNs) / 1_000_000)
        if (previous > 0 && submittedNs > previous) {
            if (intervals.size >= 300) intervals.removeAt(0)
            intervals.add((submittedNs - previous) / 1_000_000)
        }
        previous = submittedNs
        if (++submittedCount % 60 == 0) save()
    }

    @Synchronized fun save() {
        if (format !in HeadsetLink.formats || delays.size < 30 || intervals.size < 29) return
        fun p95(values: List<Long>) = values.sorted()[((values.size - 1) * .95).toInt()]
        context.getSharedPreferences(preferencesName(directWifi), Context.MODE_PRIVATE).edit()
            .putLong("$format-render", p95(delays)).putLong("$format-interval", p95(intervals))
            .putInt("$format-samples", delays.size).putLong("$format-time", System.currentTimeMillis()).apply()
    }

    companion object {
        private fun preferencesName(directWifi: Boolean) = if (directWifi) "headset-direct-link-timing" else "headset-link-timing"

        fun read(context: Context, directWifi: Boolean = false): Map<String, HeadsetLinkPolicy.Timing> {
            val preferences = context.getSharedPreferences(preferencesName(directWifi), Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            return HeadsetLink.formats.mapNotNull { format ->
                val age = now - preferences.getLong("$format-time", 0)
                if (age !in 0..86_400_000) null else format to HeadsetLinkPolicy.Timing(
                    preferences.getLong("$format-render", -1), preferences.getLong("$format-interval", -1), preferences.getInt("$format-samples", 0))
            }.toMap()
        }
    }
}
