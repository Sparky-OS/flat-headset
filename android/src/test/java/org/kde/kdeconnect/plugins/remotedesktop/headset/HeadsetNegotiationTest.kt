/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.*
import android.content.res.Resources
import android.content.Intent
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.kde.kdeconnect_tp.R
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
    @Test fun missingOfferIsExplainedAtDeadlineAndKeepsThePictureAndChoice() {
        val activity = Robolectric.buildActivity(HeadsetActivity::class.java).get()
        val application = RuntimeEnvironment.getApplication()
        val messages = object : Resources(application.assets, application.resources.displayMetrics, application.resources.configuration) {
            override fun getString(id: Int): String = if (id == R.string.headset_hevc_no_offer)
                "The computer did not offer HEVC. H.264 is shown while you choose." else "Back to choices"
        }
        ReflectionHelpers.setField(activity, "mResources", messages)
        val settings = activity.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, 0)
        settings.edit().putBoolean("hevc", true).commit()
        val panel = PanelView(application, true)
        val video = HeadsetVideo(panel) { true }
        ReflectionHelpers.setField(activity, "panel", panel)
        ReflectionHelpers.setField(activity, "video", video)
        ReflectionHelpers.setField(activity, "displayConnected", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "armOfferDeadline")
        val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999))
            assertNull(ReflectionHelpers.getField<Intent?>(activity, "codecNotice"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(ReflectionHelpers.getField<Intent>(activity, "codecNotice").getBooleanExtra(HeadsetFailure.CODEC, false))
            assertTrue(settings.getBoolean("hevc", false))
            assertEquals("A late offer cannot silently replace the explained H.264 picture", 0, video.receive(byteArrayOf(1)))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDesktopFrame",
                ReflectionHelpers.ClassParameter.from(Bitmap::class.java, frame))
            assertEquals(1L, ReflectionHelpers.getField<AtomicLong>(panel, "timingSequence").get())
            val notice = ReflectionHelpers.getField<android.view.ViewGroup>(activity, "codecNoticeView")
            assertEquals(2, notice.childCount)
            assertEquals((notice.getChildAt(0) as android.widget.TextView).text,
                (notice.getChildAt(1) as android.widget.TextView).text)
        } finally { frame.recycle(); video.close(); panel.release(); panel.onPause() }
    }

}
