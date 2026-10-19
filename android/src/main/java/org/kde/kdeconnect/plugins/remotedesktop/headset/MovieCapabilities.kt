/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.media.MediaCodecList

/** Platform decoder declarations, separate from the display's physical geometry. */
class MovieCapabilities {
    private val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
    private val types = decoders.flatMap { it.supportedTypes.toList() }.toSet()
    val sink: String = buildList {
        if ("video/avc" in types || "video/hevc" in types) {
            add("http-get:*:video/mp4:*")
            add("http-get:*:video/mp2t:*")
        }
        if (types.any { it.startsWith("video/") }) add("http-get:*:video/x-matroska:*")
        if ("video/x-vnd.on2.vp8" in types || "video/x-vnd.on2.vp9" in types) add("http-get:*:video/webm:*")
    }.joinToString(",")

    fun description(): String = "<stereo:decoders>" + types.sorted().filter {
        it.startsWith("video/") || it.startsWith("audio/")
    }.joinToString("") { type ->
        val profiles = decoders.filter { type in it.supportedTypes }.flatMap {
            runCatching { it.getCapabilitiesForType(type).profileLevels.toList() }.getOrDefault(emptyList())
        }.map { it.profile to it.level }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        "<stereo:codec mime=\"${MovieServices.escape(type)}\">" + profiles.joinToString("") {
            "<stereo:profile id=\"${it.first}\" level=\"${it.second}\"/>"
        } + "</stereo:codec>"
    } + "</stereo:decoders>"
}
