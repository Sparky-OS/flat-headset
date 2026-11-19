/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.graphics.Bitmap
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HeadsetNegotiationTest {
    @Test fun selectedHevcWithoutAnActiveSessionStillSubmitsTheDesktop() {
        val activity = Robolectric.buildActivity(HeadsetActivity::class.java).get()
        activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, 0)
            .edit().putBoolean("hevc", true).commit()
        val panel = mockk<PanelView>(relaxed = true)
        ReflectionHelpers.setField(activity, "panel", panel)
        val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        activity.onDesktopFrame(frame)
        verify(exactly = 1) { panel.submit(frame) }
        frame.recycle()
    }
}
