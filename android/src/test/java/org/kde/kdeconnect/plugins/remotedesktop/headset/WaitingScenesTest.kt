/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Activity
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WaitingScenesTest {
    private val entry = """{"id":"ball","connect":"throw.mp4","reconnect":"drop.mkv","still":"rest.png"}"""

    @Test fun catalogueRequiresLocalSeparateConnectReconnectAndStillAssets() {
        val scene = WaitingScenes.parse("[$entry]").single()
        assertEquals("throw.mp4", scene.connect)
        assertEquals("drop.mkv", scene.reconnect)
        assertEquals("rest.png", scene.still)
        for (bad in listOf("../throw.mp4", "https://example.invalid/throw.mp4", "throw.png"))
            assertTrue(WaitingScenes.parse("[${entry.replace("throw.mp4",bad)}]").isEmpty())
        assertTrue(WaitingScenes.parse("[$entry,$entry]").isEmpty())
        assertTrue(WaitingScenes.parse("not JSON").isEmpty())
        assertTrue(WaitingScenes.parse("[]").isEmpty())
    }

    @Test fun eachNewWaitAvoidsThePreviousSportWhenThereIsAChoice() {
        val first = WaitingScenes.parse("[$entry]").single()
        val second = first.copy(id = "other")
        repeat(20) { assertEquals(second, WaitingScenes.choose(listOf(first, second), first.id, Random(it))) }
        assertEquals(first, WaitingScenes.choose(listOf(first), first.id))
        assertNull(WaitingScenes.choose(emptyList(), null))
    }

    private fun png(mode: Int?, corrupt: Boolean = false, lateMark: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            data.writeInt(bytes.size); data.writeBytes(type); data.write(bytes)
            val crc = CRC32().apply { update(type.toByteArray()); update(bytes) }.value.toInt()
            data.writeInt(if (corrupt && type == "sTER") crc xor 1 else crc)
        }
        if (lateMark) chunk("IDAT", byteArrayOf())
        if (mode != null) chunk("sTER", byteArrayOf(mode.toByte()))
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    @Test fun stillUsesTheStandardParallelStereoMarkAndRejectsWrongEyeOrderAndCorruption() {
        assertTrue(WaitingScenes.isLeftFirstPng(png(1).inputStream()))
        assertFalse(WaitingScenes.isLeftFirstPng(png(0).inputStream()))
        assertFalse(WaitingScenes.isLeftFirstPng(png(1, lateMark = true).inputStream()))
        assertFalse(WaitingScenes.isLeftFirstPng(png(null).inputStream()))
        assertFalse(WaitingScenes.isLeftFirstPng(png(1,true).inputStream()))
        assertFalse(WaitingScenes.isLeftFirstPng(png(1).dropLast(12).toByteArray().inputStream()))
    }

    @Test fun softwareDecodersDoNotBecomeHardwareJustBecauseTheLegacyFlagIsWrong() {
        assertTrue(WaitingScenes.hardwareDecoder("OMX.vendor.avc.decoder", true, false))
        assertFalse(WaitingScenes.hardwareDecoder("OMX.google.h264.decoder", true, false))
        assertFalse(WaitingScenes.hardwareDecoder("c2.android.avc.decoder", true, false))
        assertFalse(WaitingScenes.hardwareDecoder("OMX.vendor.avc.decoder", true, true))
        assertFalse(WaitingScenes.hardwareDecoder("OMX.vendor.avc.decoder", false, false))
    }

    @Test fun absentArtworkHasAVisiblePerEyeWaitAndReleasesItOnArrivalPauseAndClose() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = WaitingSceneView(activity.get(), true)
        activity.get().setContentView(view)
        view.setWaiting(true)
        assertEquals(View.GONE, view.visibility)
        view.setResumed(true)
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(1, view.childCount)
        view.setWaiting(false)
        assertEquals(View.GONE, view.visibility)
        view.setWaiting(true, true)
        assertEquals(View.VISIBLE, view.visibility)
        view.setResumed(false)
        assertEquals(View.GONE, view.visibility)
        view.setResumed(true)
        assertEquals(View.VISIBLE, view.visibility)
        view.close()
        view.setWaiting(true)
        assertEquals(View.GONE, view.visibility)
        activity.pause().stop().destroy()
    }
    @Test fun decoderReleaseCannotReenterForTheSamePlayer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = WaitingSceneView(activity.get(), true)
        val field = WaitingSceneView::class.java.getDeclaredField("player").apply { isAccessible = true }
        var releases = 0
        val player = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)
        ) { _, method, _ ->
            if (method.name == "release") {
                releases++
                assertEquals("A synchronous decoder callback must not release its owner twice", 1, releases)
                view.close()
            }
            null
        }
        field.set(view, player)
        view.close()
        assertEquals(1, releases)
        assertNull(field.get(view))
        activity.pause().stop().destroy()
    }

}
