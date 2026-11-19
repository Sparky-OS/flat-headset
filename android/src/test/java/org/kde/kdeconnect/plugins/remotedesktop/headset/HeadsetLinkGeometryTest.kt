/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.app.Application
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [WaitingSceneFailureTest.NoCardboard::class])
class HeadsetLinkGeometryTest {
    @Test fun everyLinkAcceptsItsWireRasterAndRejectsUnnegotiatedGeometry() {
        val cases = listOf(Triple("native", 2340, 1080), Triple("sbs-720", 2560, 720),
            Triple("tb-720", 1280, 1440), Triple("sbs-half-720", 1280, 720), Triple("tb-half-720", 1280, 720))
        for ((format, width, height) in cases) {
            val panel = PanelView(RuntimeEnvironment.getApplication(), true, linkFormat = format)
            ReflectionHelpers.setField(panel, "panelWidth", 2340)
            ReflectionHelpers.setField(panel, "panelHeight", 1080)
            try {
                assertTrue(format, panel.linkSizeMatches(width, height))
                assertFalse(format, panel.linkSizeMatches(width + 2, height))
                assertFalse(format, panel.linkSizeMatches(width, height + 2))
                if (format != "native") assertFalse(format, panel.linkSizeMatches(2340, 1080))
                val planes = listOf(ByteBuffer.allocateDirect(width * height),
                    ByteBuffer.allocateDirect(width * height / 4), ByteBuffer.allocateDirect(width * height / 4))
                panel.submitDecoderPlanes(width, height, planes)
                assertEquals(width, ReflectionHelpers.getField<Int>(panel, "decoderWidth"))
                assertThrows(IllegalArgumentException::class.java) { panel.submitDecoderPlanes(width + 2, height, planes) }
                assertThrows(IllegalArgumentException::class.java) { panel.submitDecoderPlanes(width, height, planes.take(2)) }
            } finally { panel.release(); panel.onPause() }
        }
    }

    @Test fun unknownLinkAndUninitialisedNativePanelCannotAcceptAnOffer() {
        assertNull(HeadsetLink.encodedSize("unknown", 2340, 1080))
        assertNull(HeadsetLink.encodedSize("native", 0, 0))
        assertNull(HeadsetLink.encodedSize("native", 2339, 1080))
    }
}
