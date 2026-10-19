/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset

/** UPnP AVTransport:3, RenderingControl:3 and ConnectionManager:3 service descriptions. */
object MovieServices {
    fun escape(value: String) =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    data class Variable(
        val name: String,
        val type: String = "string",
        val evented: Boolean = false,
        val values: List<String> = emptyList(),
        val range: Pair<Int, Int>? = null,
    )

    data class Argument(val name: String, val variable: String, val output: Boolean)

    data class Action(val name: String, val arguments: List<Argument>)

    class Service(val name: String) {
        val id = "urn:upnp-org:serviceId:$name"
        val type = "urn:schemas-upnp-org:service:$name:3"
        val variables = linkedMapOf<String, Variable>()
        val actions = linkedMapOf<String, Action>()

        fun variable(
            name: String,
            type: String = "string",
            evented: Boolean = false,
            values: List<String> = emptyList(),
            range: Pair<Int, Int>? = null,
        ) {
            variables[name] = Variable(name, type, evented, values, range)
        }

        // Each entry is argumentName=stateVariable; a leading + marks an output argument.
        fun action(name: String, vararg entries: String) {
            actions[name] =
                Action(
                    name,
                    entries.map {
                        val parts = it.removePrefix("+").split('=')
                        check(variables.containsKey(parts[1]))
                        Argument(parts[0], parts[1], it.startsWith('+'))
                    },
                )
        }

        fun scpd(): String =
            """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>""" +
                actions.values.joinToString("") { a ->
                    "<action><name>${a.name}</name><argumentList>" +
                        a.arguments.joinToString("") {
                            "<argument><name>${it.name}</name><direction>${if(it.output) "out" else "in"}</direction><relatedStateVariable>${it.variable}</relatedStateVariable></argument>"
                        } +
                        "</argumentList></action>"
                } +
                "</actionList><serviceStateTable>" +
                variables.values.joinToString("") {
                    "<stateVariable sendEvents=\"${if(it.evented) "yes" else "no"}\"><name>${it.name}</name><dataType>${it.type}</dataType>" +
                        (if (it.values.isEmpty()) ""
                        else
                            "<allowedValueList>" +
                                it.values.joinToString("") { v ->
                                    "<allowedValue>${escape(v)}</allowedValue>"
                                } +
                                "</allowedValueList>") +
                        (it.range?.let { r ->
                            "<allowedValueRange><minimum>${r.first}</minimum><maximum>${r.second}</maximum><step>1</step></allowedValueRange>"
                        } ?: "") +
                        "</stateVariable>"
                } +
                "</serviceStateTable></scpd>"

        fun description() =
            "<service><serviceType>$type</serviceType><serviceId>$id</serviceId><SCPDURL>/$name.xml</SCPDURL><controlURL>/$name/control</controlURL><eventSubURL>/$name/event</eventSubURL></service>"
    }

    val rendering =
        Service("RenderingControl").apply {
            variable("LastChange", evented = true)
            variable("A_ARG_TYPE_InstanceID", "ui4")
            variable("A_ARG_TYPE_Channel", values = listOf("Master"))
            variable("A_ARG_TYPE_PresetName", values = listOf("FactoryDefaults"))
            variable("PresetNameList")
            variable("Brightness", "ui2", range = 0 to 100)
            variable("Volume", "ui2", range = 0 to 100)
            variable("Mute", "boolean")
            action(
                "ListPresets",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+CurrentPresetNameList=PresetNameList",
            )
            action(
                "SelectPreset",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "PresetName=A_ARG_TYPE_PresetName",
            )
            for (name in listOf("Brightness", "Volume", "Mute")) {
                val channel =
                    if (name == "Brightness") emptyArray()
                    else arrayOf("Channel=A_ARG_TYPE_Channel")
                action(
                    "Get$name",
                    "InstanceID=A_ARG_TYPE_InstanceID",
                    *channel,
                    "+Current$name=$name",
                )
                action(
                    "Set$name",
                    "InstanceID=A_ARG_TYPE_InstanceID",
                    *channel,
                    "Desired$name=$name",
                )
            }
        }
    val transport =
        Service("AVTransport").apply {
            variable("LastChange", evented = true)
            variable("A_ARG_TYPE_InstanceID", "ui4")
            variable("A_ARG_TYPE_SeekMode", values = listOf("REL_TIME", "ABS_TIME", "TRACK_NR"))
            variable("A_ARG_TYPE_SeekTarget")
            variable(
                "TransportState",
                values =
                    listOf(
                        "STOPPED",
                        "PLAYING",
                        "TRANSITIONING",
                        "PAUSED_PLAYBACK",
                        "NO_MEDIA_PRESENT",
                    ),
            )
            variable("TransportStatus", values = listOf("OK", "ERROR_OCCURRED"))
            variable("TransportPlaySpeed", values = listOf("1"))
            variable("CurrentPlayMode", values = listOf("NORMAL"))
            variable("PlaybackStorageMedium", values = listOf("NETWORK", "NONE"))
            variable("PossiblePlaybackStorageMedia")
            variable("RecordStorageMedium", values = listOf("NOT_IMPLEMENTED"))
            variable("PossibleRecordStorageMedia", values = listOf("NOT_IMPLEMENTED"))
            variable("RecordMediumWriteStatus", values = listOf("NOT_IMPLEMENTED"))
            variable("CurrentRecordQualityMode", values = listOf("NOT_IMPLEMENTED"))
            variable("PossibleRecordQualityModes", values = listOf("NOT_IMPLEMENTED"))
            variable("NumberOfTracks", "ui4")
            variable("CurrentTrack", "ui4")
            variable("CurrentMediaCategory", values = listOf("NO_MEDIA", "TRACK_AWARE"))
            for (name in
                listOf(
                    "CurrentMediaDuration",
                    "CurrentTrackDuration",
                    "CurrentTrackMetaData",
                    "CurrentTrackURI",
                    "AVTransportURI",
                    "AVTransportURIMetaData",
                    "NextAVTransportURI",
                    "NextAVTransportURIMetaData",
                    "RelativeTimePosition",
                    "AbsoluteTimePosition",
                    "CurrentTransportActions",
                )) variable(name)
            variable("RelativeCounterPosition", "i4")
            variable("AbsoluteCounterPosition", "i4")
            action(
                "SetAVTransportURI",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "CurrentURI=AVTransportURI",
                "CurrentURIMetaData=AVTransportURIMetaData",
            )
            val media =
                arrayOf(
                    "InstanceID=A_ARG_TYPE_InstanceID",
                    "+NrTracks=NumberOfTracks",
                    "+MediaDuration=CurrentMediaDuration",
                    "+CurrentURI=AVTransportURI",
                    "+CurrentURIMetaData=AVTransportURIMetaData",
                    "+NextURI=NextAVTransportURI",
                    "+NextURIMetaData=NextAVTransportURIMetaData",
                    "+PlayMedium=PlaybackStorageMedium",
                    "+RecordMedium=RecordStorageMedium",
                    "+WriteStatus=RecordMediumWriteStatus",
                )
            action("GetMediaInfo", *media)
            action(
                "GetMediaInfo_Ext",
                media[0],
                "+CurrentType=CurrentMediaCategory",
                *media.drop(1).toTypedArray(),
            )
            action(
                "GetTransportInfo",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+CurrentTransportState=TransportState",
                "+CurrentTransportStatus=TransportStatus",
                "+CurrentSpeed=TransportPlaySpeed",
            )
            action(
                "GetPositionInfo",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+Track=CurrentTrack",
                "+TrackDuration=CurrentTrackDuration",
                "+TrackMetaData=CurrentTrackMetaData",
                "+TrackURI=CurrentTrackURI",
                "+RelTime=RelativeTimePosition",
                "+AbsTime=AbsoluteTimePosition",
                "+RelCount=RelativeCounterPosition",
                "+AbsCount=AbsoluteCounterPosition",
            )
            action(
                "GetDeviceCapabilities",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+PlayMedia=PossiblePlaybackStorageMedia",
                "+RecMedia=PossibleRecordStorageMedia",
                "+RecQualityModes=PossibleRecordQualityModes",
            )
            action(
                "GetTransportSettings",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+PlayMode=CurrentPlayMode",
                "+RecQualityMode=CurrentRecordQualityMode",
            )
            action("Play", "InstanceID=A_ARG_TYPE_InstanceID", "Speed=TransportPlaySpeed")
            for (name in listOf("Pause", "Stop", "Next", "Previous")) action(
                name,
                "InstanceID=A_ARG_TYPE_InstanceID",
            )
            action(
                "Seek",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "Unit=A_ARG_TYPE_SeekMode",
                "Target=A_ARG_TYPE_SeekTarget",
            )
            action(
                "GetCurrentTransportActions",
                "InstanceID=A_ARG_TYPE_InstanceID",
                "+Actions=CurrentTransportActions",
            )
        }
    val connection =
        Service("ConnectionManager").apply {
            variable("SourceProtocolInfo", evented = true)
            variable("SinkProtocolInfo", evented = true)
            variable("CurrentConnectionIDs", evented = true)
            variable("FeatureList")
            for (name in listOf("ConnectionID", "AVTransportID", "RcsID")) variable(
                "A_ARG_TYPE_$name",
                "i4",
            )
            for (name in listOf("ProtocolInfo", "ConnectionManager")) variable("A_ARG_TYPE_$name")
            variable("A_ARG_TYPE_Direction", values = listOf("Input"))
            variable("A_ARG_TYPE_ConnectionStatus", values = listOf("OK", "Unknown"))
            action("GetProtocolInfo", "+Source=SourceProtocolInfo", "+Sink=SinkProtocolInfo")
            action("GetCurrentConnectionIDs", "+ConnectionIDs=CurrentConnectionIDs")
            action(
                "GetCurrentConnectionInfo",
                "ConnectionID=A_ARG_TYPE_ConnectionID",
                "+RcsID=A_ARG_TYPE_RcsID",
                "+AVTransportID=A_ARG_TYPE_AVTransportID",
                "+ProtocolInfo=A_ARG_TYPE_ProtocolInfo",
                "+PeerConnectionManager=A_ARG_TYPE_ConnectionManager",
                "+PeerConnectionID=A_ARG_TYPE_ConnectionID",
                "+Direction=A_ARG_TYPE_Direction",
                "+Status=A_ARG_TYPE_ConnectionStatus",
            )
            action("GetFeatureList", "+FeatureList=FeatureList")
        }
    val all = listOf(transport, rendering, connection)
}
