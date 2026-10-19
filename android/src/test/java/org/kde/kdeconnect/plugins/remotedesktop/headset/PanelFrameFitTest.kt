/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import org.junit.Assert.*
import org.junit.Test

class PanelFrameFitTest {
    @Test fun modeCycleKeepsBothEyesInThePanel() {
        assertEquals(PanelFrameFit.Size(1170, 1080), PanelFrameFit.fit(2340, 1080, 2340, 1080))
        assertEquals(PanelFrameFit.Size(1170, 658), PanelFrameFit.fit(2560, 720, 2340, 1080))
        assertEquals(PanelFrameFit.Size(1170, 1080), PanelFrameFit.fit(2340, 1080, 2340, 1080))
        assertEquals(PanelFrameFit.Size(540, 1080), PanelFrameFit.fit(1080, 1080, 2340, 1080))
        assertNull(PanelFrameFit.fit(2559, 720, 2340, 1080))
        assertNull(PanelFrameFit.fit(0, 720, 2340, 1080))
    }
}
