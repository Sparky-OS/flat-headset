/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadsetFitTest {
    @Test fun marginsFollowEitherLandscapeOrientation() {
        assertEquals(80, symmetricEyeMargin(2340, 80, 0))
        assertEquals(80, symmetricEyeMargin(2340, 0, 80))
        assertEquals(90, symmetricEyeMargin(2340, 80, 90))
    }
    @Test fun noCutoutLeavesTheWholeImageVisible() {
        assertEquals(0, symmetricEyeMargin(2340, 0, 0))
    }
    @Test fun invalidInsetsCannotHideAnEntireEye() {
        assertEquals(584, symmetricEyeMargin(2340, 2000, 0))
        assertEquals(0, symmetricEyeMargin(0, 80, 0))
    }
}
