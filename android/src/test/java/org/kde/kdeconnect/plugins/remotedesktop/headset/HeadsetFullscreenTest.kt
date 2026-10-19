/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.presentation.SessionInputManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** Exercise the real FreeRDP bar policy without starting its native RDP session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class,
    shadows = [HeadsetFullscreenTest.DefaultBarSettings::class, HeadsetFullscreenTest.NoActionBar::class])
@Suppress("DEPRECATION")
class HeadsetFullscreenTest {
    // Keep the real session/window policy; replace only the preference XML and
    // AppCompat chrome that these resource-free JVM tests do not inflate.
    @Implements(ApplicationSettingsActivity::class)
    class DefaultBarSettings {
        companion object {
            @JvmStatic @Implementation fun getHideStatusBar(context: Context): Boolean = false
            @JvmStatic @Implementation fun getHideNavigationBar(context: Context): Boolean = false
        }
    }

    @Implements(AppCompatActivity::class)
    class NoActionBar : org.robolectric.shadows.ShadowActivity() {
        @Implementation fun getSupportActionBar(): ActionBar? = null
    }

    private fun viewer(): HeadsetActivity =
        Robolectric.buildActivity(HeadsetActivity::class.java).get()

    private fun connectionChanged(activity: HeadsetActivity, connected: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onSessionConnectionChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, connected))
    }

    private fun applyInheritedBarPolicy(activity: HeadsetActivity) {
        // bindSession() calls this after the initial window-focus event.
        SessionActivity::class.java.getDeclaredMethod("hideSystemBars").apply {
            isAccessible = true
        }.invoke(activity)
    }

    private fun assertImmersive(activity: HeadsetActivity) {
        val required = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        assertEquals(required, activity.window.decorView.systemUiVisibility and required)
    }

    @Test fun connectionRestoresFullscreenWithoutAnotherFocusEvent() {
        val activity = viewer()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        applyInheritedBarPolicy(activity)
        assertFalse(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)

        connectionChanged(activity, true)
        assertImmersive(activity)

        // A reconnect must recover too, without the notification-drawer workaround.
        connectionChanged(activity, false)
        applyInheritedBarPolicy(activity)
        connectionChanged(activity, true)
        assertImmersive(activity)
    }

    @Test fun configurationChangeKeepsTheViewerFullscreen() {
        val activity = viewer()
        ReflectionHelpers.setField(activity, "inputManager", mockk<SessionInputManager>(relaxed = true))
        connectionChanged(activity, true)
        activity.onConfigurationChanged(Configuration(activity.resources.configuration))
        assertImmersive(activity)
    }
}
