/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [WaitingSceneFailureTest.NoCardboard::class])
class HeadsetNegotiationTest {
    @Test fun selectedHevcWithoutAnActiveSessionStillSubmitsTheDesktop() {
        val activity = Robolectric.buildActivity(HeadsetActivity::class.java).get()
        activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, 0)
            .edit().putBoolean("hevc", true).commit()
        val panel = PanelView(RuntimeEnvironment.getApplication(), true)
        ReflectionHelpers.setField(activity, "panel", panel)
        val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDesktopFrame",
            ReflectionHelpers.ClassParameter.from(Bitmap::class.java, frame))
        try {
            assertEquals(1L, ReflectionHelpers.getField<AtomicLong>(panel, "timingSequence").get())
        } finally {
            frame.recycle()
            panel.release()
            panel.onPause()
        }
    }
}
