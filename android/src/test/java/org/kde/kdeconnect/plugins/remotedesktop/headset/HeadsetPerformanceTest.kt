/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.os.Looper
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HeadsetPerformanceTest {
    @Implements(PowerManager::class)
    class SupportedPowerManager : ShadowPowerManager() {
        @Implementation protected fun isSustainedPerformanceModeSupported(): Boolean = true
    }

    @Test @Config(shadows = [SupportedPowerManager::class])
    fun supportedSustainedModeFollowsTheWindowLifecycle() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertTrue((activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isSustainedPerformanceModeSupported)
        val flag: Int = ReflectionHelpers.getStaticField(WindowManager.LayoutParams::class.java, "PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE")
        fun sustained(): Boolean = ReflectionHelpers.getField<Int>(activity.window.attributes, "privateFlags") and flag != 0
        val performance = HeadsetPerformance(activity)
        assertFalse(sustained())
        performance.setResumed(true)
        performance.setConnected(true)
        assertTrue(sustained())
        performance.setResumed(false)
        assertFalse(sustained())
        performance.setResumed(true)
        assertTrue(sustained())
        performance.close()
        assertFalse(sustained())
        controller.pause().stop().destroy()
    }

    @Test fun resourcesFollowVisibleConnectionAndReleaseOnFailureOrDestroy() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val performance = HeadsetPerformance(activity)
        fun screenOn() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        performance.setResumed(true)
        assertFalse(performance.active)
        assertFalse(screenOn())
        performance.setConnected(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertTrue(screenOn())
        performance.setConnected(true)
        performance.setResumed(false)
        assertFalse(lock.isHeld)
        assertFalse(screenOn())
        performance.setResumed(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lock.isHeld)
        performance.setConnected(false)
        assertFalse(lock.isHeld)
        assertFalse(screenOn())
        performance.setConnected(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        performance.close()
        assertFalse(lock.isHeld)
        assertFalse(screenOn())
        performance.setConnected(true)
        performance.setResumed(true)
        assertFalse(performance.active)
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [23]) fun unsupportedSustainedModeStillReleasesTheLease() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val performance = HeadsetPerformance(controller.get())
        performance.setConnected(true)
        assertFalse(performance.active)
        performance.setResumed(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        performance.close()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        controller.pause().stop().destroy()
    }
}
