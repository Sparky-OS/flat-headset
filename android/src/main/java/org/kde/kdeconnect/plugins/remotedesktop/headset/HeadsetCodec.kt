/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

internal object HeadsetCodec {
    // Legacy OMX.google entries omit the software-only flag in Android's OmxInfoBuilder.
    fun isSoftware(name: String, declaredSoftware: Boolean): Boolean = declaredSoftware ||
        name.startsWith("OMX.google.") || name.startsWith("c2.android.")
}
