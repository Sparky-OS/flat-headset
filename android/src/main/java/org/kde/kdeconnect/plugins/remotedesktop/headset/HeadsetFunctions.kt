/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import androidx.annotation.MainThread
import java.lang.ref.WeakReference

/** Explicit function selection ends the other function. It never transfers a live session. */
object HeadsetFunctions {
    const val EXTRA_TRACKED = "headsetTrackedFunction"
    private data class Owner(val activity: WeakReference<Activity>, val tracked: Boolean, val stop: () -> Unit)
    private val owners = mutableListOf<Owner>()

    @MainThread
    fun enter(activity: Activity, tracked: Boolean, stop: () -> Unit) {
        val previous = owners.filter { it.tracked != tracked }
        owners.removeAll(previous.toSet())
        // End viewers before their callers so native disconnect can deliver its result.
        previous.sortedBy { if (it.activity.get() is HeadsetActivity) 0 else 1 }
            .forEach { if (it.activity.get()?.isDestroyed == false) it.stop() }
        watch(activity, tracked, stop)
    }

    @MainThread
    fun watch(activity: Activity, tracked: Boolean, stop: () -> Unit) {
        forget(activity)
        owners.add(Owner(WeakReference(activity), tracked, stop))
    }

    @MainThread
    fun forget(activity: Activity) {
        owners.removeAll { it.activity.get() == null || it.activity.get() === activity }
    }
}
