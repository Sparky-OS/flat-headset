/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HeadsetWifiTest {
    @Test fun lockFollowsVisibleConnectedSessionWithoutLeakingAcrossReconnect() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val wifi = Shadows.shadowOf(activity.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        val performance = HeadsetPerformance(activity)
        val baseline = wifi.activeLockCount
        performance.setResumed(true)
        assertEquals(baseline, wifi.activeLockCount)
        performance.setConnected(true)
        assertEquals(baseline + 1, wifi.activeLockCount)
        performance.setConnected(true)
        assertEquals(baseline + 1, wifi.activeLockCount)
        performance.setResumed(false)
        assertEquals(baseline, wifi.activeLockCount)
        performance.setResumed(true)
        assertEquals(baseline + 1, wifi.activeLockCount)
        performance.setConnected(false)
        assertEquals(baseline, wifi.activeLockCount)
        performance.close()
        performance.setConnected(true)
        assertEquals(baseline, wifi.activeLockCount)
        controller.pause().stop().destroy()
    }
    @Test @Config(sdk = [23]) fun unsupportedApiDoesNotRequestALock() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val wifi = Shadows.shadowOf(controller.get().getSystemService(Context.WIFI_SERVICE) as WifiManager)
        val lock = HeadsetWifi(controller.get())
        val baseline = wifi.activeLockCount
        lock.setActive(true)
        assertEquals(baseline, wifi.activeLockCount)
        lock.close()
        controller.pause().stop().destroy()
    }
}
