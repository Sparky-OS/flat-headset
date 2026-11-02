/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import android.media.MediaCodec
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.After
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [WaitingSceneFailureTest.NoCardboard::class])
class HeadsetVideoFailureTest {
    private val panels = mutableListOf<PanelView>()
    @After fun cleanup() { panels.forEach { it.release(); it.onPause() } }
    private fun receiver(decoder: MediaCodec): HeadsetVideo = HeadsetVideo(PanelView(RuntimeEnvironment.getApplication(), true).also { panels.add(it) }) { true }.also {
        ReflectionHelpers.setField(it, "codec", decoder)
        ReflectionHelpers.setField(it, "width", 640)
        ReflectionHelpers.setField(it, "height", 480)
        ReflectionHelpers.setField(it, "rate", 60000)
    }

    private fun packet(width: Int = 640): ByteArray = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN).apply {
        listOf(1, 3, width, 480, 60000, 1, 1, 1, 2).forEach { putInt(it) }
        put(0)
    }.array()

    @Test fun inputTimeoutStopsTheSessionInsteadOfRequestingH264() {
        val decoder = mockk<MediaCodec>(relaxed = true)
        every { decoder.dequeueInputBuffer(any()) } returns -1
        assertEquals(-1, receiver(decoder).receive(packet()))
    }

    @Test fun decoderFailureStopsTheSessionInsteadOfRequestingH264() {
        val decoder = mockk<MediaCodec>(relaxed = true)
        every { decoder.dequeueInputBuffer(any()) } throws IllegalStateException("decoder rejected access unit")
        assertEquals(-1, receiver(decoder).receive(packet()))
    }

    @Test fun geometryRejectionStopsTheSessionInsteadOfRequestingH264() {
        assertEquals(-1, receiver(mockk(relaxed = true)).receive(packet(320)))
    }
    @Test fun defaultH264DeclinesTheOptionalHevcChannel() {
        val context = RuntimeEnvironment.getApplication()
        val settings = context.getSharedPreferences(HeadsetFitOverlay.PREFERENCES, 0)
        settings.edit().clear().commit()
        val panel = PanelView(context, true).also { panels.add(it) }
        val video = HeadsetVideo(panel) { settings.getBoolean("hevc", false) }
        assertEquals(0, video.receive(packet()))
    }

}
