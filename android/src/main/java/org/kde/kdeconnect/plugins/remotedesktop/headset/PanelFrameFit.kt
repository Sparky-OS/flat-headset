/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import kotlin.math.min
import kotlin.math.roundToInt

internal object PanelFrameFit {
    data class Size(val width: Int, val height: Int)

    fun fit(width: Int, height: Int, panelWidth: Int, panelHeight: Int): Size? {
        if (width <= 0 || width % 2 != 0 || height <= 0 || width > 16384 || height > 16384 ||
            panelWidth <= 0 || panelWidth % 2 != 0 || panelHeight <= 0) return null
        val scale = min(panelWidth.toDouble() / width, panelHeight.toDouble() / height)
        return Size((width / 2 * scale).roundToInt(), (height * scale).roundToInt())
    }
}
