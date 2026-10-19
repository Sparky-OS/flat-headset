/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import org.junit.Assert.*
import org.junit.Test

class HeadsetLinkPolicyTest {
    private val strong = HeadsetWifiSample(6, 5180, 600, 600, -45)
    @Test fun unknownLinkKeepsNativeAndManualChoicesRemainAvailable() {
        assertEquals(HeadsetLink.NATIVE, HeadsetLink.DEFAULT)
        assertEquals(5, HeadsetLink.formats.size)
        assertEquals(HeadsetLink.NATIVE, HeadsetLinkPolicy.choose(null, emptyMap()).format)
        assertEquals(HeadsetLink.NATIVE, HeadsetLinkPolicy.choose(strong.copy(rxMbps = -1), emptyMap()).format)
    }
    @Test fun phyHintsDoNotOverrideMeasuredSlowRendering() {
        val timing = mapOf(HeadsetLink.NATIVE to HeadsetLinkPolicy.Timing(280, 300, 90),
            HeadsetLink.HALF_TB_720 to HeadsetLinkPolicy.Timing(25, 35, 90))
        val choice = HeadsetLinkPolicy.choose(strong, timing)
        assertEquals(HeadsetLink.HALF_TB_720, choice.format)
        assertEquals(HeadsetLinkPolicy.Reason.MEASURED_DELAY, choice.reason)
    }
    @Test fun everyWireFormatCanWinFromMeasurements() {
        for (winner in HeadsetLink.formats) {
            val timing = HeadsetLink.formats.associateWith { HeadsetLinkPolicy.Timing(if (it == winner) 20 else 200, if (it == winner) 25 else 210, 60) }
            assertEquals(winner, HeadsetLinkPolicy.choose(strong, timing).format)
        }
    }
    @Test fun slowWifiPrefersHalfSamplingAndIncompleteEvidenceIsIgnored() {
        val slow = strong.copy(rxMbps = 54, rssi = -78)
        assertEquals(HeadsetLink.HALF_TB_720, HeadsetLinkPolicy.choose(slow, emptyMap()).format)
        val timing = mapOf(HeadsetLink.NATIVE to HeadsetLinkPolicy.Timing(1, 1, 90),
            HeadsetLink.HALF_SBS_720 to HeadsetLinkPolicy.Timing(10, 20, 29))
        assertEquals(HeadsetLink.HALF_TB_720, HeadsetLinkPolicy.choose(slow, timing).format)
        assertEquals(HeadsetLink.SBS_720, HeadsetLinkPolicy.choose(strong.copy(rxMbps = 200), emptyMap()).format)
    }
    @Test fun wifiBandsAndStandardsDoNotInventUnavailableDetails() {
        assertEquals("5 GHz", strong.band)
        assertEquals("802.11ax", strong.standardName)
        assertEquals("6 GHz", strong.copy(frequency = 6115).band)
        assertEquals("?", strong.copy(frequency = -1).band)
        assertEquals("802.11?", strong.copy(standard = 0).standardName)
    }
}
