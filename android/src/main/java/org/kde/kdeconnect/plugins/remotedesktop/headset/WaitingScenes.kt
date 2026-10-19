/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import org.json.JSONArray
import java.io.DataInputStream
import java.io.InputStream
import kotlin.random.Random

/** Local artwork only. Stereo layout is read from the media, never from this catalogue. */
internal object WaitingScenes {
    const val DIRECTORY = "headset-waiting"
    const val REDUCE_MOTION = "waiting-reduce-motion"
    data class Scene(val id: String, val connect: String, val reconnect: String, val still: String)

    fun parse(text: String): List<Scene> {
        if (text.length > 65536) return emptyList()
        return try {
            val array = JSONArray(text)
            require(array.length() <= 32)
            val scenes = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                fun filename(key: String): String = item.getString(key).also {
                    require(it.matches(Regex("[a-zA-Z0-9_-]+\\.(mp4|mkv|png)")))
                    require(if (key == "still") it.endsWith(".png") else !it.endsWith(".png"))
                }
                Scene(item.getString("id").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) },
                    filename("connect"), filename("reconnect"), filename("still"))
            }
            require(scenes.map { it.id }.distinct().size == scenes.size)
            scenes
        } catch (_: Exception) { emptyList() }
    }

    fun hardwareDecoder(name: String, hardware: Boolean, software: Boolean): Boolean =
        hardware && !HeadsetCodec.isSoftware(name, software)

    fun choose(scenes: List<Scene>, previous: String?, random: Random = Random.Default): Scene? =
        scenes.filter { scenes.size == 1 || it.id != previous }.randomOrNull(random)

    /** PNG sTER mode 1 is parallel (left first); a still without that mark is not a stereo asset. */
    fun isLeftFirstPng(input: InputStream): Boolean = try {
        val stream = DataInputStream(input)
        val signature = ByteArray(8).also { stream.readFully(it) }
        require(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        var consumed = 8L
        var marked = false
        var seen = false
        var ended = false
        var imageData = false
        while (consumed < 16 * 1024 * 1024) {
            val size = stream.readInt()
            require(size in 0..16 * 1024 * 1024 && consumed + size + 12 <= 16 * 1024 * 1024)
            val type = ByteArray(4).also { stream.readFully(it) }.toString(Charsets.US_ASCII)
            if (type == "IDAT") imageData = true
            var markerCrc: Int? = null
            if (type == "sTER") {
                require(size == 1 && !seen && !imageData)
                seen = true
                val mode = stream.readUnsignedByte()
                marked = mode == 1
                markerCrc = java.util.zip.CRC32().apply { update("sTER".toByteArray(Charsets.US_ASCII)); update(mode) }.value.toInt()
            } else {
                var remaining = size
                while (remaining > 0) {
                    val skipped = stream.skipBytes(remaining)
                    require(skipped > 0)
                    remaining -= skipped
                }
            }
            val crc = stream.readInt()
            require(markerCrc == null || crc == markerCrc)
            consumed += size.toLong() + 12
            if (type == "IEND") { require(size == 0); ended = true; break }
        }
        marked && ended
    } catch (_: Exception) { false }
}
