package dev.rusty.app.renderer

/**
 * GENA NOTIFY event bodies. Every `LastChange` state variable value is itself a small
 * UPnP XML document; the encoding is two layers deep (spec's double-escaping trap):
 * each attribute VALUE inside the inner `<Event>` doc is escaped once via
 * [UpnpXml.escape], then the *entire* inner doc (already containing `&amp;` etc.) is
 * escaped again exactly once when it is embedded as the text content of the outer
 * `<LastChange>` element. HA's `async_upnp_client` unescapes the outer propertyset XML,
 * then parses the resulting string as its own XML document, so this depth is required
 * for it to round-trip correctly.
 */
object UpnpEventXml {
    private fun propertyset(vararg properties: Pair<String, String>): String {
        val body = properties.joinToString("") { (name, value) -> "<e:property><$name>$value</$name></e:property>" }
        return "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">$body</e:propertyset>"
    }

    private fun lastChangeProperty(inner: String): String =
        "<e:property><LastChange>${UpnpXml.escape(inner)}</LastChange></e:property>"

    fun avTransportLastChange(state: RendererState): String {
        val media = state.media
        val duration = if (media == null) "0:00:00" else UpnpFormats.formatTime(state.durationMs)
        val numberOfTracks = if (media == null) "0" else "1"
        val currentTrack = if (media == null) "0" else "1"
        val inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
            "<InstanceID val=\"0\">" +
            "<TransportState val=\"${UpnpXml.escape(state.transport.name)}\"/>" +
            "<TransportStatus val=\"${UpnpXml.escape(state.transportStatus)}\"/>" +
            "<AVTransportURI val=\"${UpnpXml.escape(media?.uri ?: "")}\"/>" +
            "<CurrentTrackURI val=\"${UpnpXml.escape(media?.uri ?: "")}\"/>" +
            "<CurrentTrackMetaData val=\"${UpnpXml.escape(media?.metadata ?: "")}\"/>" +
            "<CurrentTransportActions val=\"${UpnpXml.escape(state.currentTransportActions())}\"/>" +
            "<CurrentTrackDuration val=\"${UpnpXml.escape(duration)}\"/>" +
            "<NumberOfTracks val=\"${UpnpXml.escape(numberOfTracks)}\"/>" +
            "<CurrentTrack val=\"${UpnpXml.escape(currentTrack)}\"/>" +
            "</InstanceID></Event>"
        return "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">${lastChangeProperty(inner)}</e:propertyset>"
    }

    fun renderingControlLastChange(volumePercent: Int, muted: Boolean): String {
        val inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">" +
            "<InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${UpnpXml.escape(volumePercent.toString())}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (muted) "1" else "0"}\"/>" +
            "</InstanceID></Event>"
        return "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">${lastChangeProperty(inner)}</e:propertyset>"
    }

    fun connectionManagerInitial(): String = propertyset(
        "SourceProtocolInfo" to "",
        "SinkProtocolInfo" to UpnpFormats.sinkProtocolInfo(),
        "CurrentConnectionIDs" to "0",
    )
}
