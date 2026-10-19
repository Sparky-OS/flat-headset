/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

internal object PanelNetworkInterface {
    fun select(available: List<String>, active: String?, direct: String?): String {
        if (direct != null) {
            require(direct in available) { "Direct Wi-Fi interface is not ready" }
            return direct
        }
        return available.firstOrNull { it == active }
            ?: available.firstOrNull { it.startsWith("rndis") || it.startsWith("usb") || it == "eth0" }
            ?: error("No local network interface")
    }
}
