/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import java.nio.ByteBuffer

/** Pack only the visible samples; decoder padding and interleaved chroma stay out. */
internal object HeadsetPlanes {
    fun pack(source: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
             width: Int, height: Int, destination: ByteBuffer) {
        require(width > 0 && height > 0 && left >= 0 && top >= 0 && pixelStride > 0 && rowStride > 0)
        require((left.toLong() + width - 1) * pixelStride + 1 <= rowStride)
        val first = source.position().toLong() + top.toLong() * rowStride + left.toLong() * pixelStride
        val last = first + (height - 1L) * rowStride + (width - 1L) * pixelStride
        require(last < source.limit() && width.toLong() * height <= destination.capacity())
        destination.clear()
        for (y in 0 until height) {
            val offset = (first + y.toLong() * rowStride).toInt()
            if (pixelStride == 1) {
                val row = source.duplicate()
                row.position(offset); row.limit(offset + width)
                destination.put(row)
            } else {
                for (x in 0 until width) destination.put(source.get(offset + x * pixelStride))
            }
        }
        destination.flip()
    }
}
