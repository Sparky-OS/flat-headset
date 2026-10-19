/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package com.freerdp.freerdpcore.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadPointerControllerTest {
    @Test
    fun relativeMovementStartsAtDesktopCenter() {
        val controller = TouchpadPointerController()
        controller.setBounds(1920, 1080)
        controller.configure(240f, "default", "noacceleration")

        val position = controller.moveBy(30f, -20f, 1_000)

        assertEquals(990, position.x)
        assertEquals(520, position.y)
    }

    @Test
    fun dpiNormalizationAndRemoteInputSensitivityAreApplied() {
        val controller = TouchpadPointerController()
        controller.setBounds(1000, 1000)
        controller.configure(480f, "fastest", "noacceleration")

        val position = controller.moveBy(25f, 10f, 1_000)

        assertEquals(525, position.x)
        assertEquals(510, position.y)
    }

    @Test
    fun cursorNeverLeavesTheRdpDesktop() {
        val controller = TouchpadPointerController()
        controller.setBounds(100, 80)
        controller.configure(240f, "default", "noacceleration")

        var position = controller.moveBy(500f, 500f, 1_000)
        assertEquals(99, position.x)
        assertEquals(79, position.y)

        position = controller.moveBy(-500f, -500f, 1_010)
        assertEquals(0, position.x)
        assertEquals(0, position.y)
    }
}
