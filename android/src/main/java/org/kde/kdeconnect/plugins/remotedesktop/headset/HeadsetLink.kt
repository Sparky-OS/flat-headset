/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

internal object HeadsetLink {
    const val PREFERENCE = "link-format"
    const val EXTRA = "headsetLinkFormat"
    const val NATIVE = "native"
    const val SBS_720 = "sbs-720"
    const val HALF_TB_720 = "tb-half-720"
    const val HALF_SBS_720 = "sbs-half-720"
    const val TB_720 = "tb-720"
    const val DEFAULT = NATIVE
    const val AUTOMATIC = "automatic"
    val formats = listOf(NATIVE, SBS_720, HALF_TB_720, HALF_SBS_720, TB_720)
    fun label(format: String): Int = when (format) {
        SBS_720 -> org.kde.kdeconnect_tp.R.string.headset_link_sbs720
        HALF_TB_720 -> org.kde.kdeconnect_tp.R.string.headset_link_tb720
        HALF_SBS_720 -> org.kde.kdeconnect_tp.R.string.headset_link_halfsbs720
        TB_720 -> org.kde.kdeconnect_tp.R.string.headset_link_fulltb720
        AUTOMATIC -> org.kde.kdeconnect_tp.R.string.headset_link_automatic
        else -> org.kde.kdeconnect_tp.R.string.headset_link_native
    }
    val choices = formats + AUTOMATIC
}
