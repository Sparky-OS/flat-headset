/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.util.Log
import org.kde.kdeconnect_tp.BuildConfig

/** Explicit diagnostic build only. No images, addresses or credentials are recorded. */
internal object HeadsetTiming {
    fun record(stage: String, id: Long) {
        if (BuildConfig.DEBUG && BuildConfig.HEADSET_TIMING) {
            Log.i("HeadsetStage", "stage=$stage ns=${System.nanoTime()} id=$id")
        }
    }
}
