/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadsetPlanesTest {
    @Test fun cropsPaddedRowsWithoutChangingDecoderPosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 0, 1, 2, 3, 88, 4, 5, 6, 7, 88, 8, 9, 10, 11, 88))
        source.position(1)
        val packed = ByteBuffer.allocateDirect(4)
        HeadsetPlanes.pack(source, 5, 1, 1, 1, 2, 2, packed)
        val bytes = ByteArray(4); packed.get(bytes)
        assertArrayEquals(byteArrayOf(5, 6, 9, 10), bytes)
        assertEquals(1, source.position())
    }
    @Test fun separatesInterleavedChromaWithShortLastRow() {
        val source = ByteBuffer.wrap(byteArrayOf(10, 90, 20, 80, 0, 0, 30, 70, 40))
        val packed = ByteBuffer.allocateDirect(4)
        HeadsetPlanes.pack(source, 6, 2, 0, 0, 2, 2, packed)
        val bytes = ByteArray(4); packed.get(bytes)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), bytes)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedPlane() {
        HeadsetPlanes.pack(ByteBuffer.allocate(8), 6, 2, 0, 0, 2, 2, ByteBuffer.allocate(4))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOverlappingRows() {
        HeadsetPlanes.pack(ByteBuffer.allocate(8), 3, 1, 0, 0, 4, 2, ByteBuffer.allocate(8))
    }
}
