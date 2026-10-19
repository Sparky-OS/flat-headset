/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetCodecTest {
    @Test fun recognizesLegacyPlatformDecoderWithoutSoftwareFlag() {
        assertTrue(HeadsetCodec.isSoftware("OMX.google.hevc.decoder", false))
    }
    @Test fun recognizesPlatformAndDeclaredSoftwareCodecs() {
        assertTrue(HeadsetCodec.isSoftware("c2.android.hevc.decoder", false))
        assertTrue(HeadsetCodec.isSoftware("vendor.software.hevc", true))
    }
    @Test fun retainsSurfacePathForHardwareAndUnknownVendorCodecs() {
        assertFalse(HeadsetCodec.isSoftware("c2.vendor.hevc.decoder", false))
        assertFalse(HeadsetCodec.isSoftware("OMX.vendor.hevc.decoder", false))
        assertFalse(HeadsetCodec.isSoftware("prefix.OMX.google.hevc.decoder", false))
    }
}
