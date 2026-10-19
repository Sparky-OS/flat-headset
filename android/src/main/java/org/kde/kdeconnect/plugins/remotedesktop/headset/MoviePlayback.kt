/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

import android.os.Handler
import android.os.Looper
import android.util.Xml
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.StringReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.kde.kdeconnect.headset.upnp.NativeRenderer
import org.xmlpull.v1.XmlPullParser

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MoviePlayback(
    val player: ExoPlayer,
    private val brightness: () -> Int,
    private val setBrightness: (Int) -> Unit,
    private val capabilities: MovieCapabilities,
) : NativeRenderer.Listener {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var closed = false
    var changed: (() -> Unit)? = null
    private var uri = ""
    private var metadata = ""
    private var volume = 100
    private var muted = false
    private var stopped = true

    private class Fault(val code: Int) : RuntimeException()

    private val sink get() = capabilities.sink

    init {
        player.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    changed?.invoke()
                }
            }
        )
    }

    private fun <T> onMain(work: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val task = FutureTask { work() }
        main.post(task)
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun arguments(xml: String): Map<String, String> {
        if (xml.contains("<!DOCTYPE", true) || xml.length > 65536) throw Fault(402)
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val values = linkedMapOf<String, String>()
        var depth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                depth++
                if (depth == 2) {
                    val name = parser.name
                    if (values.containsKey(name)) throw Fault(402)
                    values[name] = parser.nextText()
                    depth--
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) depth--
            parser.next()
        }
        return values
    }

    override fun onAction(serviceId: String, action: String, requestXml: String): String =
        try {
            val service = MovieServices.all.firstOrNull { it.id == serviceId } ?: throw Fault(401)
            val description = service.actions[action] ?: throw Fault(401)
            val args = arguments(requestXml)
            if (args.keys != description.arguments.filter { !it.output }.map { it.name }.toSet())
                throw Fault(402)
            val output = onMain {
                if (closed) throw Fault(501)
                if (service != MovieServices.connection && args["InstanceID"] != "0")
                    throw Fault(if (service == MovieServices.transport) 718 else 702)
                if (args.containsKey("Channel") && args["Channel"] != "Master") throw Fault(402)
                val result =
                    when (service) {
                        MovieServices.rendering -> rendering(action, args)
                        MovieServices.transport -> transport(action, args)
                        else -> connection(action, args)
                    }
                changed?.invoke()
                result
            }
            "<u:${action}Response xmlns:u=\"${service.type}\">" +
                description.arguments
                    .filter { it.output }
                    .joinToString("") {
                        "<${it.name}>${MovieServices.escape(output.getValue(it.name))}</${it.name}>"
                    } +
                "</u:${action}Response>"
        } catch (error: Exception) {
            var cause: Throwable = error
            while (cause.cause != null) cause = cause.cause!!
            "!${(cause as? Fault)?.code ?: 501}"
        }

    private fun percent(value: String?): Int =
        value?.toIntOrNull()?.takeIf { it in 0..100 } ?: throw Fault(402)

    private fun rendering(action: String, args: Map<String, String>): Map<String, String> {
        when (action) {
            "ListPresets" -> return mapOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> {
                if (args["PresetName"] != "FactoryDefaults") throw Fault(701)
                setBrightness(50)
                volume = 100
                muted = false
            }
            "GetBrightness" -> return mapOf("CurrentBrightness" to brightness().toString())
            "SetBrightness" -> setBrightness(percent(args["DesiredBrightness"]))
            "GetVolume" -> return mapOf("CurrentVolume" to volume.toString())
            "SetVolume" -> volume = percent(args["DesiredVolume"])
            "GetMute" -> return mapOf("CurrentMute" to if (muted) "1" else "0")
            "SetMute" ->
                muted =
                    when (args["DesiredMute"]) {
                        "1",
                        "true" -> true
                        "0",
                        "false" -> false
                        else -> throw Fault(402)
                    }
        }
        player.volume = if (muted) 0f else volume / 100f
        return emptyMap()
    }

    private fun time(value: Long): String {
        val seconds = value.coerceAtLeast(0) / 1000
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            seconds / 3600,
            (seconds / 60) % 60,
            seconds % 60,
        )
    }

    private fun duration() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0

    private fun state() =
        when {
            uri.isEmpty() -> "NO_MEDIA_PRESENT"
            stopped || player.playbackState == Player.STATE_ENDED -> "STOPPED"
            player.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
            player.playWhenReady -> "PLAYING"
            else -> "PAUSED_PLAYBACK"
        }

    private fun actions() =
        when (state()) {
            "NO_MEDIA_PRESENT" -> ""
            "PLAYING",
            "TRANSITIONING" -> "Stop,Pause,Seek"
            else -> "Play,Stop,Seek"
        }

    private fun mediaInfo() =
        mapOf(
            "NrTracks" to if (uri.isEmpty()) "0" else "1",
            "MediaDuration" to time(duration()),
            "CurrentURI" to uri,
            "CurrentURIMetaData" to metadata,
            "NextURI" to "",
            "NextURIMetaData" to "",
            "PlayMedium" to if (uri.isEmpty()) "NONE" else "NETWORK",
            "RecordMedium" to "NOT_IMPLEMENTED",
            "WriteStatus" to "NOT_IMPLEMENTED",
        )

    private fun transport(action: String, args: Map<String, String>): Map<String, String> {
        when (action) {
            "SetAVTransportURI" -> {
                val value = args.getValue("CurrentURI")
                if (value.isNotEmpty()) {
                    val parsed =
                        try {
                            URI(value)
                        } catch (_: Exception) {
                            throw Fault(714)
                        }
                    if (
                        parsed.scheme !in listOf("http", "https") ||
                            parsed.host.isNullOrEmpty() ||
                            parsed.rawUserInfo != null
                    )
                        throw Fault(714)
                }
                player.stop()
                player.clearMediaItems()
                stopped = true
                uri = value
                metadata = args.getValue("CurrentURIMetaData")
                if (uri.isNotEmpty()) {
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                }
            }
            "GetMediaInfo" -> return mediaInfo()
            "GetMediaInfo_Ext" ->
                return mediaInfo() +
                    mapOf("CurrentType" to if (uri.isEmpty()) "NO_MEDIA" else "TRACK_AWARE")
            "GetTransportInfo" ->
                return mapOf(
                    "CurrentTransportState" to state(),
                    "CurrentTransportStatus" to
                        if (player.playerError == null) "OK" else "ERROR_OCCURRED",
                    "CurrentSpeed" to "1",
                )
            "GetPositionInfo" ->
                return mapOf(
                    "Track" to if (uri.isEmpty()) "0" else "1",
                    "TrackDuration" to time(duration()),
                    "TrackMetaData" to metadata,
                    "TrackURI" to uri,
                    "RelTime" to time(player.currentPosition),
                    "AbsTime" to time(player.currentPosition),
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            "GetDeviceCapabilities" ->
                return mapOf(
                    "PlayMedia" to "NETWORK",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                )
            "GetTransportSettings" ->
                return mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> return mapOf("Actions" to actions())
            "Play" -> {
                if (uri.isEmpty()) throw Fault(701)
                if (args["Speed"] != "1") throw Fault(717)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                stopped = false
                player.play()
            }
            "Pause" -> {
                if (state() !in listOf("PLAYING", "TRANSITIONING")) throw Fault(701)
                player.pause()
            }
            "Stop" -> {
                if (uri.isEmpty()) throw Fault(701)
                player.pause()
                player.seekTo(0)
                stopped = true
            }
            "Seek" -> {
                if (uri.isEmpty()) throw Fault(701)
                val target = args.getValue("Target")
                val position =
                    when (args["Unit"]) {
                        "TRACK_NR" -> if (target == "1") 0L else throw Fault(711)
                        "REL_TIME",
                        "ABS_TIME" -> {
                            if (
                                !target.matches(
                                    Regex("[0-9]+:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,3})?")
                                )
                            )
                                throw Fault(711)
                            val parts = target.split(':')
                            val milliseconds =
                                ((parts[0].toDouble() * 3600 +
                                    parts[1].toInt() * 60 +
                                    parts[2].toDouble()) * 1000)
                            if (!milliseconds.isFinite() || milliseconds > Long.MAX_VALUE)
                                throw Fault(711)
                            milliseconds.toLong()
                        }
                        else -> throw Fault(710)
                    }
                if (duration() > 0 && position > duration()) throw Fault(711)
                if (!player.isCurrentMediaItemSeekable) throw Fault(710)
                player.seekTo(position)
            }
            "Next",
            "Previous" -> throw Fault(711)
        }
        return emptyMap()
    }

    private fun connection(action: String, args: Map<String, String>): Map<String, String> =
        when (action) {
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to sink)
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> {
                if (args["ConnectionID"] != "0") throw Fault(706)
                mapOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                )
            }
            "GetFeatureList" ->
                mapOf("FeatureList" to "<Features xmlns=\"urn:schemas-upnp-org:av:cm-features\"/>")
            else -> throw Fault(401)
        }

    override fun snapshot(serviceId: String): String = onMain {
        if (closed) return@onMain ""
        val service = MovieServices.all.first { it.id == serviceId }
        val values =
            when (service) {
                MovieServices.connection ->
                    mapOf(
                        "SourceProtocolInfo" to "",
                        "SinkProtocolInfo" to sink,
                        "CurrentConnectionIDs" to "0",
                    )
                else -> {
                    val entries =
                        if (service == MovieServices.rendering)
                            listOf(
                                "<Brightness val=\"${brightness()}\"/>",
                                "<Volume channel=\"Master\" val=\"$volume\"/>",
                                "<Mute channel=\"Master\" val=\"${if(muted) 1 else 0}\"/>",
                                "<PresetNameList val=\"FactoryDefaults\"/>",
                            )
                        else
                            listOf(
                                "<TransportState val=\"${state()}\"/>",
                                "<TransportStatus val=\"${if(player.playerError==null) "OK" else "ERROR_OCCURRED"}\"/>",
                                "<AVTransportURI val=\"${MovieServices.escape(uri)}\"/>",
                                "<CurrentTrackURI val=\"${MovieServices.escape(uri)}\"/>",
                                "<CurrentTrackDuration val=\"${time(duration())}\"/>",
                                "<CurrentTransportActions val=\"${actions()}\"/>",
                            )
                    mapOf(
                        "LastChange" to
                            "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/${if(service==MovieServices.rendering) "RCS" else "AVT"}/\"><InstanceID val=\"0\">${entries.joinToString("")}</InstanceID></Event>"
                    )
                }
            }
        "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            values.entries.joinToString("") { (name, value) ->
                "<e:property><$name>${MovieServices.escape(value)}</$name></e:property>"
            } +
            "</e:propertyset>"
    }
}
