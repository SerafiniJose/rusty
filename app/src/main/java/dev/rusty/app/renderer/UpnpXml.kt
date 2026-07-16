package dev.rusty.app.renderer

/**
 * The UPnP device-description and SCPD (Service Control Protocol Description) XML
 * documents for the Rusty MediaRenderer. HA's `dlna_dmr` integration (built on
 * `async_upnp_client`) parses these to (a) accept the device at all — it requires the
 * root device's `deviceType` to be `MediaRenderer:1/2/3` and the three serviceIds below
 * to be present — and (b) derive feature support (play/pause/seek/volume/mute) purely
 * from the declared actions and state variables. Getting an action's argumentList or a
 * referenced state variable wrong here silently breaks a feature on the HA side with no
 * error visible on this side, so these documents are transcribed exactly per the task
 * brief rather than generated ad hoc.
 */
enum class UpnpService(val pathSegment: String, val serviceType: String, val serviceId: String) {
    AVTRANSPORT("avtransport", "urn:schemas-upnp-org:service:AVTransport:1", "urn:upnp-org:serviceId:AVTransport"),
    RENDERINGCONTROL("renderingcontrol", "urn:schemas-upnp-org:service:RenderingControl:1", "urn:upnp-org:serviceId:RenderingControl"),
    CONNECTIONMANAGER("connectionmanager", "urn:schemas-upnp-org:service:ConnectionManager:1", "urn:upnp-org:serviceId:ConnectionManager"),
}

object UpnpXml {
    const val DESCRIPTION_PATH = "/upnp/device.xml"
    fun scpdPath(svc: UpnpService) = "/upnp/scpd/${svc.pathSegment}.xml"
    fun controlPath(svc: UpnpService) = "/upnp/control/${svc.pathSegment}"
    fun eventPath(svc: UpnpService) = "/upnp/event/${svc.pathSegment}"

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    fun deviceDescription(friendlyName: String, udn: String, configId: Long): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" configId="$configId">
        <specVersion><major>1</major><minor>0</minor></specVersion>
        <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>${escape(friendlyName)}</friendlyName>
        <manufacturer>Rusty</manufacturer>
        <manufacturerURL>https://github.com/SerafiniJose/rusty</manufacturerURL>
        <modelName>Rusty Media Renderer</modelName>
        <modelNumber>1</modelNumber>
        <UDN>${escape(udn)}</UDN>
        <serviceList>
        ${UpnpService.values().joinToString("\n") { svc -> """
        <service>
        <serviceType>${svc.serviceType}</serviceType>
        <serviceId>${svc.serviceId}</serviceId>
        <SCPDURL>${scpdPath(svc)}</SCPDURL>
        <controlURL>${controlPath(svc)}</controlURL>
        <eventSubURL>${eventPath(svc)}</eventSubURL>
        </service>""" }}
        </serviceList>
        </device>
        </root>
    """.trimIndent()

    private const val SCPD_HEADER = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>"""

    private fun action(name: String, args: List<Triple<String, String, String>>): String {
        val argList = if (args.isEmpty()) "" else """
<argumentList>
${args.joinToString("\n") { (argName, direction, relatedStateVariable) -> """<argument>
<name>$argName</name>
<direction>$direction</direction>
<relatedStateVariable>$relatedStateVariable</relatedStateVariable>
</argument>""" }}
</argumentList>"""
        return """<action>
<name>$name</name>$argList
</action>"""
    }

    /** (name, direction "in"/"out", relatedStateVariable) */
    private fun inArg(name: String, relatedStateVariable: String) = Triple(name, "in", relatedStateVariable)
    private fun outArg(name: String, relatedStateVariable: String) = Triple(name, "out", relatedStateVariable)

    private fun stringStateVariable(
        name: String,
        sendEvents: Boolean = false,
        allowedValues: List<String>? = null,
    ): String {
        val allowed = if (allowedValues == null) "" else """
<allowedValueList>
${allowedValues.joinToString("\n") { "<allowedValue>$it</allowedValue>" }}
</allowedValueList>"""
        return """<stateVariable sendEvents="${if (sendEvents) "yes" else "no"}">
<name>$name</name>
<dataType>string</dataType>$allowed
</stateVariable>"""
    }

    private fun stateVariable(
        name: String,
        dataType: String,
        sendEvents: Boolean = false,
    ): String = """<stateVariable sendEvents="${if (sendEvents) "yes" else "no"}">
<name>$name</name>
<dataType>$dataType</dataType>
</stateVariable>"""

    // ---------------------------------------------------------------------
    // AVTransport:1
    // ---------------------------------------------------------------------

    val AVTRANSPORT_SCPD: String = run {
        val actions = listOf(
            action(
                "SetAVTransportURI", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("CurrentURI", "AVTransportURI"),
                    inArg("CurrentURIMetaData", "AVTransportURIMetaData"),
                )
            ),
            action(
                "GetMediaInfo", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("NrTracks", "NumberOfTracks"),
                    outArg("MediaDuration", "CurrentMediaDuration"),
                    outArg("CurrentURI", "AVTransportURI"),
                    outArg("CurrentURIMetaData", "AVTransportURIMetaData"),
                    outArg("NextURI", "NextAVTransportURI"),
                    outArg("NextURIMetaData", "NextAVTransportURIMetaData"),
                    outArg("PlayMedium", "PlaybackStorageMedium"),
                    outArg("RecordMedium", "RecordStorageMedium"),
                    outArg("WriteStatus", "RecordMediumWriteStatus"),
                )
            ),
            action(
                "GetTransportInfo", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("CurrentTransportState", "TransportState"),
                    outArg("CurrentTransportStatus", "TransportStatus"),
                    outArg("CurrentSpeed", "TransportPlaySpeed"),
                )
            ),
            action(
                "GetPositionInfo", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("Track", "CurrentTrack"),
                    outArg("TrackDuration", "CurrentTrackDuration"),
                    outArg("TrackMetaData", "CurrentTrackMetaData"),
                    outArg("TrackURI", "CurrentTrackURI"),
                    outArg("RelTime", "RelativeTimePosition"),
                    outArg("AbsTime", "AbsoluteTimePosition"),
                    outArg("RelCount", "RelativeCounterPosition"),
                    outArg("AbsCount", "AbsoluteCounterPosition"),
                )
            ),
            action(
                "GetDeviceCapabilities", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("PlayMedia", "PossiblePlaybackStorageMedia"),
                    outArg("RecMedia", "PossibleRecordStorageMedia"),
                    outArg("RecQualityModes", "PossibleRecordQualityModes"),
                )
            ),
            action(
                "GetTransportSettings", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("PlayMode", "CurrentPlayMode"),
                    outArg("RecQualityMode", "CurrentRecordQualityMode"),
                )
            ),
            action("Stop", listOf(inArg("InstanceID", "A_ARG_TYPE_InstanceID"))),
            action(
                "Play", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Speed", "TransportPlaySpeed"),
                )
            ),
            action("Pause", listOf(inArg("InstanceID", "A_ARG_TYPE_InstanceID"))),
            action(
                "Seek", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Unit", "A_ARG_TYPE_SeekMode"),
                    inArg("Target", "A_ARG_TYPE_SeekTarget"),
                )
            ),
            action(
                "GetCurrentTransportActions", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    outArg("Actions", "CurrentTransportActions"),
                )
            ),
        )

        val stateVariables = listOf(
            stringStateVariable(
                "TransportState",
                allowedValues = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT"),
            ),
            stringStateVariable("TransportStatus", allowedValues = listOf("OK", "ERROR_OCCURRED")),
            stringStateVariable("PlaybackStorageMedium"),
            stringStateVariable("RecordStorageMedium"),
            stringStateVariable("PossiblePlaybackStorageMedia"),
            stringStateVariable("PossibleRecordStorageMedia"),
            stringStateVariable("CurrentPlayMode", allowedValues = listOf("NORMAL")),
            stringStateVariable("TransportPlaySpeed", allowedValues = listOf("1")),
            stringStateVariable("RecordMediumWriteStatus"),
            stringStateVariable("CurrentRecordQualityMode"),
            stringStateVariable("PossibleRecordQualityModes"),
            stateVariable("NumberOfTracks", "ui4"),
            stateVariable("CurrentTrack", "ui4"),
            stringStateVariable("CurrentTrackDuration"),
            stringStateVariable("CurrentMediaDuration"),
            stringStateVariable("CurrentTrackMetaData"),
            stringStateVariable("CurrentTrackURI"),
            stringStateVariable("AVTransportURI"),
            stringStateVariable("AVTransportURIMetaData"),
            stringStateVariable("NextAVTransportURI"),
            stringStateVariable("NextAVTransportURIMetaData"),
            stringStateVariable("RelativeTimePosition"),
            stringStateVariable("AbsoluteTimePosition"),
            stateVariable("RelativeCounterPosition", "i4"),
            stateVariable("AbsoluteCounterPosition", "i4"),
            stringStateVariable("CurrentTransportActions"),
            stringStateVariable("LastChange", sendEvents = true),
            stringStateVariable("A_ARG_TYPE_SeekMode", allowedValues = listOf("REL_TIME", "TRACK_NR")),
            stringStateVariable("A_ARG_TYPE_SeekTarget"),
            stateVariable("A_ARG_TYPE_InstanceID", "ui4"),
        )

        """$SCPD_HEADER
<actionList>
${actions.joinToString("\n")}
</actionList>
<serviceStateTable>
${stateVariables.joinToString("\n")}
</serviceStateTable>
</scpd>"""
    }

    // ---------------------------------------------------------------------
    // RenderingControl:1
    // ---------------------------------------------------------------------

    fun renderingControlScpd(volumeFixed: Boolean): String {
        val actions = if (volumeFixed) emptyList() else listOf(
            action(
                "GetVolume", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Channel", "A_ARG_TYPE_Channel"),
                    outArg("CurrentVolume", "Volume"),
                )
            ),
            action(
                "SetVolume", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Channel", "A_ARG_TYPE_Channel"),
                    inArg("DesiredVolume", "Volume"),
                )
            ),
            action(
                "GetMute", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Channel", "A_ARG_TYPE_Channel"),
                    outArg("CurrentMute", "Mute"),
                )
            ),
            action(
                "SetMute", listOf(
                    inArg("InstanceID", "A_ARG_TYPE_InstanceID"),
                    inArg("Channel", "A_ARG_TYPE_Channel"),
                    inArg("DesiredMute", "Mute"),
                )
            ),
        )

        val stateVariables = mutableListOf(
            stringStateVariable("LastChange", sendEvents = true),
            stringStateVariable("A_ARG_TYPE_Channel", allowedValues = listOf("Master")),
            stateVariable("A_ARG_TYPE_InstanceID", "ui4"),
        )
        if (!volumeFixed) {
            stateVariables += """<stateVariable sendEvents="no">
<name>Volume</name>
<dataType>ui2</dataType>
<allowedValueRange>
<minimum>0</minimum>
<maximum>100</maximum>
<step>1</step>
</allowedValueRange>
</stateVariable>"""
            stateVariables += stateVariable("Mute", "boolean")
        }

        val actionListXml = if (actions.isEmpty()) "<actionList/>" else """<actionList>
${actions.joinToString("\n")}
</actionList>"""

        return """$SCPD_HEADER
$actionListXml
<serviceStateTable>
${stateVariables.joinToString("\n")}
</serviceStateTable>
</scpd>"""
    }

    // ---------------------------------------------------------------------
    // ConnectionManager:1
    // ---------------------------------------------------------------------

    val CONNECTIONMANAGER_SCPD: String = run {
        val actions = listOf(
            action(
                "GetProtocolInfo", listOf(
                    outArg("Source", "SourceProtocolInfo"),
                    outArg("Sink", "SinkProtocolInfo"),
                )
            ),
            action(
                "GetCurrentConnectionIDs", listOf(
                    outArg("ConnectionIDs", "CurrentConnectionIDs"),
                )
            ),
            action(
                "GetCurrentConnectionInfo", listOf(
                    inArg("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                    outArg("RcsID", "A_ARG_TYPE_RcsID"),
                    outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                    outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                    outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                    outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                    outArg("Direction", "A_ARG_TYPE_Direction"),
                    outArg("Status", "A_ARG_TYPE_ConnectionStatus"),
                )
            ),
        )

        val stateVariables = listOf(
            stringStateVariable("SourceProtocolInfo", sendEvents = true),
            stringStateVariable("SinkProtocolInfo", sendEvents = true),
            stringStateVariable("CurrentConnectionIDs", sendEvents = true),
            stateVariable("A_ARG_TYPE_ConnectionID", "i4"),
            stateVariable("A_ARG_TYPE_RcsID", "i4"),
            stateVariable("A_ARG_TYPE_AVTransportID", "i4"),
            stringStateVariable("A_ARG_TYPE_ProtocolInfo"),
            stringStateVariable("A_ARG_TYPE_ConnectionManager"),
            stringStateVariable("A_ARG_TYPE_Direction", allowedValues = listOf("Input", "Output")),
            stringStateVariable(
                "A_ARG_TYPE_ConnectionStatus",
                allowedValues = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"),
            ),
        )

        """$SCPD_HEADER
<actionList>
${actions.joinToString("\n")}
</actionList>
<serviceStateTable>
${stateVariables.joinToString("\n")}
</serviceStateTable>
</scpd>"""
    }
}
