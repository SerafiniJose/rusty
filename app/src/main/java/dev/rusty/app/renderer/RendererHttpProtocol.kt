package dev.rusty.app.renderer

import java.io.InputStream

/** Parsed HTTP request; header keys are uppercased for case-insensitive lookup. */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

data class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: String,
) {
    fun render(): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val allHeaders = headers + ("Content-Length" to bodyBytes.size.toString()) + ("Connection" to "close")
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        for ((k, v) in allHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        sb.append(body)
        return sb.toString()
    }
}

/** The seam between HTTP and the live renderer system; the server is handed one. */
interface RendererRuntime {
    val friendlyName: String
    val udn: String
    /** UPnP CONFIGID: bumped whenever the device description changes (i.e. on rename). */
    val configId: Long
    val volumeFixed: Boolean
    val rendererState: RendererState
    fun dispatch(event: RendererEvent)
    fun positionMs(): Long                       // live player position (0 when idle)
    fun spotifySnapshot(): Pair<Boolean, Long>   // (playing, sessionGeneration) for SoapPlay events
    /** The user's announcement mix mode, read from prefs AT DISPATCH TIME — the pure reducer
     *  never touches prefs; it records the mode as the interruption owner. */
    fun mixMode(): SpotifyInterruption
    /** The user's announcement fade duration (ms, 0 = off), read from prefs AT DISPATCH TIME. */
    fun fadeMs(): Long
    fun volumePercent(): Int
    fun setVolumePercent(v: Int)
    fun muted(): Boolean
    fun setMuted(m: Boolean)
    fun onVolumeChanged()                        // poke eventing after Set*
    fun gena(): GenaSubscriptions
    fun onSubscribed(sub: GenaSubscriptions.Sub) // triggers the immediate initial NOTIFY
}

/**
 * Pure request parser + SOAP/GENA router for the MediaRenderer HTTP endpoint. No I/O
 * beyond reading the already-open [InputStream] handed in by the server; entirely
 * testable off-device.
 */
object RendererHttpProtocol {
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""

    // -------------------------------------------------------------------
    // Request parsing
    // -------------------------------------------------------------------

    fun parseRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx < 0) return null
            val key = line.substring(0, idx).trim().uppercase()
            val value = line.substring(idx + 1).trim()
            headers[key] = value
        }

        val contentLength = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        val body = String(bodyBytes, Charsets.UTF_8)
        return HttpRequest(method, path, headers, body)
    }

    /** Reads a single CRLF-terminated line (without the CRLF); null on EOF before a line completes.
     *  Internal (not private) so [RendererHttpServer] can reuse it for reading NOTIFY status lines. */
    internal fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prevWasCr = false
        while (true) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            if (prevWasCr && c == '\n') {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c)
            prevWasCr = c == '\r'
            if (sb.length > MAX_BODY_BYTES) return null
        }
    }

    // -------------------------------------------------------------------
    // Router
    // -------------------------------------------------------------------

    fun route(req: HttpRequest, rt: RendererRuntime, subscriberIp: String): HttpResponse {
        val path = req.path
        return when {
            req.method == "GET" && path == UpnpXml.DESCRIPTION_PATH ->
                xmlOk(UpnpXml.deviceDescription(rt.friendlyName, rt.udn, rt.configId))

            req.method == "GET" && path.startsWith("/upnp/scpd/") && path.endsWith(".xml") -> {
                val svc = UpnpService.values().firstOrNull { path == UpnpXml.scpdPath(it) }
                if (svc == null) notFound() else xmlOk(scpdFor(svc, rt))
            }

            req.method == "POST" && path.startsWith("/upnp/control/") -> {
                val svc = UpnpService.values().firstOrNull { path == UpnpXml.controlPath(it) }
                if (svc == null) notFound() else handleControl(req, svc, rt)
            }

            req.method == "SUBSCRIBE" && path.startsWith("/upnp/event/") -> {
                val svc = UpnpService.values().firstOrNull { path == UpnpXml.eventPath(it) }
                if (svc == null) notFound() else handleSubscribe(req, svc, rt, subscriberIp)
            }

            req.method == "UNSUBSCRIBE" && path.startsWith("/upnp/event/") -> {
                val svc = UpnpService.values().firstOrNull { path == UpnpXml.eventPath(it) }
                if (svc == null) notFound() else handleUnsubscribe(req, rt)
            }

            else -> notFound()
        }
    }

    private fun scpdFor(svc: UpnpService, rt: RendererRuntime): String = when (svc) {
        UpnpService.AVTRANSPORT -> UpnpXml.AVTRANSPORT_SCPD
        UpnpService.RENDERINGCONTROL -> UpnpXml.renderingControlScpd(rt.volumeFixed)
        UpnpService.CONNECTIONMANAGER -> UpnpXml.CONNECTIONMANAGER_SCPD
    }

    private fun xmlOk(body: String): HttpResponse =
        HttpResponse(200, "OK", listOf("Content-Type" to XML_CONTENT_TYPE), body)

    private fun notFound(): HttpResponse = HttpResponse(404, "Not Found", emptyList(), "")

    private fun soapOk(service: UpnpService, action: String, outArgs: List<Pair<String, String>>): HttpResponse =
        HttpResponse(200, "OK", listOf("Content-Type" to XML_CONTENT_TYPE), SoapProtocol.response(service, action, outArgs))

    private fun soapFault(code: Int, description: String): HttpResponse =
        HttpResponse(500, "Internal Server Error", listOf("Content-Type" to XML_CONTENT_TYPE), SoapProtocol.fault(code, description))

    // -------------------------------------------------------------------
    // SOAP control
    // -------------------------------------------------------------------

    private val DIDL_MIME_REGEX = Regex("protocolInfo=\"http-get:[^:]*:([^:;\"]+)")

    private fun mimeFromDidl(metadata: String?): String? {
        if (metadata.isNullOrEmpty()) return null
        return DIDL_MIME_REGEX.find(metadata)?.groupValues?.get(1)
    }

    private fun handleControl(req: HttpRequest, svc: UpnpService, rt: RendererRuntime): HttpResponse {
        val soapReq = SoapProtocol.parse(req.headers["SOAPACTION"], req.body)
            ?: return soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")
        if (soapReq.service != svc) return soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")

        return when (svc) {
            UpnpService.AVTRANSPORT -> handleAvTransport(soapReq, rt)
            UpnpService.RENDERINGCONTROL -> handleRenderingControl(soapReq, rt)
            UpnpService.CONNECTIONMANAGER -> handleConnectionManager(soapReq)
        }
    }

    private fun handleAvTransport(req: SoapRequest, rt: RendererRuntime): HttpResponse {
        val state = rt.rendererState
        return when (req.action) {
            "SetAVTransportURI" -> {
                val uri = req.args["CurrentURI"]
                val metadata = req.args["CurrentURIMetaData"].orEmpty()
                val mimeHint = mimeFromDidl(req.args["CurrentURIMetaData"])
                when (val validation = UpnpFormats.validateUri(uri, mimeHint)) {
                    UpnpFormats.Validation.BadUri -> soapFault(SoapProtocol.ERR_RESOURCE_NOT_FOUND, "Invalid URI")
                    UpnpFormats.Validation.UnsupportedMime -> soapFault(SoapProtocol.ERR_ILLEGAL_MIME, "Unsupported MIME type")
                    is UpnpFormats.Validation.Ok -> {
                        rt.dispatch(RendererEvent.SoapSetUri(validation.uri, metadata, validation.mime))
                        soapOk(req.service, req.action, emptyList())
                    }
                }
            }

            "Play" -> {
                if (state.media == null) soapFault(SoapProtocol.ERR_TRANSITION_NOT_AVAILABLE, "Transition not available")
                else {
                    val (playing, gen) = rt.spotifySnapshot()
                    rt.dispatch(RendererCommandTranslator.toEvent(RendererCommand.Play, playing, gen, rt.mixMode(), rt.fadeMs()))
                    soapOk(req.service, req.action, emptyList())
                }
            }

            "Pause" -> {
                rt.dispatch(RendererEvent.SoapPause)
                soapOk(req.service, req.action, emptyList())
            }

            "Stop" -> {
                rt.dispatch(RendererEvent.SoapStop)
                soapOk(req.service, req.action, emptyList())
            }

            "Seek" -> {
                val unit = req.args["Unit"]
                if (unit != "REL_TIME") soapFault(SoapProtocol.ERR_SEEKMODE_NOT_SUPPORTED, "Seek mode not supported")
                else {
                    val target = UpnpFormats.parseTime(req.args["Target"])
                    if (target == null || !state.seekable) soapFault(SoapProtocol.ERR_ILLEGAL_SEEK_TARGET, "Illegal seek target")
                    else {
                        rt.dispatch(RendererEvent.SoapSeek(target))
                        soapOk(req.service, req.action, emptyList())
                    }
                }
            }

            "GetTransportInfo" -> soapOk(req.service, req.action, listOf(
                "CurrentTransportState" to state.transport.name,
                "CurrentTransportStatus" to state.transportStatus,
                "CurrentSpeed" to "1",
            ))

            "GetPositionInfo" -> {
                val hasMedia = state.media != null
                soapOk(req.service, req.action, listOf(
                    "Track" to if (hasMedia) "1" else "0",
                    "TrackDuration" to UpnpFormats.formatTime(state.durationMs),
                    "TrackMetaData" to (state.media?.metadata ?: ""),
                    "TrackURI" to (state.media?.uri ?: ""),
                    "RelTime" to if (hasMedia) UpnpFormats.formatTime(rt.positionMs()) else "NOT_IMPLEMENTED",
                    "AbsTime" to "NOT_IMPLEMENTED",
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                ))
            }

            "GetMediaInfo" -> soapOk(req.service, req.action, listOf(
                "NrTracks" to if (state.media != null) "1" else "0",
                "MediaDuration" to UpnpFormats.formatTime(state.durationMs),
                "CurrentURI" to (state.media?.uri ?: ""),
                "CurrentURIMetaData" to (state.media?.metadata ?: ""),
                "NextURI" to "",
                "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            ))

            "GetDeviceCapabilities" -> soapOk(req.service, req.action, listOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            ))

            "GetTransportSettings" -> soapOk(req.service, req.action, listOf(
                "PlayMode" to "NORMAL",
                "RecQualityMode" to "NOT_IMPLEMENTED",
            ))

            "GetCurrentTransportActions" -> soapOk(req.service, req.action, listOf(
                "Actions" to state.currentTransportActions(),
            ))

            else -> soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")
        }
    }

    private fun handleRenderingControl(req: SoapRequest, rt: RendererRuntime): HttpResponse {
        if (rt.volumeFixed) return soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")
        return when (req.action) {
            "GetVolume" -> soapOk(req.service, req.action, listOf("CurrentVolume" to rt.volumePercent().toString()))

            "SetVolume" -> {
                val desired = req.args["DesiredVolume"]?.toIntOrNull()
                    ?: return soapFault(SoapProtocol.ERR_INVALID_ARGS, "Invalid Args")
                rt.setVolumePercent(desired.coerceIn(0, 100))
                rt.onVolumeChanged()
                soapOk(req.service, req.action, emptyList())
            }

            "GetMute" -> soapOk(req.service, req.action, listOf("CurrentMute" to if (rt.muted()) "1" else "0"))

            "SetMute" -> {
                val desired = req.args["DesiredMute"]
                    ?: return soapFault(SoapProtocol.ERR_INVALID_ARGS, "Invalid Args")
                rt.setMuted(desired == "1" || desired.equals("true", ignoreCase = true))
                rt.onVolumeChanged()
                soapOk(req.service, req.action, emptyList())
            }

            else -> soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")
        }
    }

    private fun handleConnectionManager(req: SoapRequest): HttpResponse = when (req.action) {
        "GetProtocolInfo" -> soapOk(req.service, req.action, listOf(
            "Source" to "",
            "Sink" to UpnpFormats.sinkProtocolInfo(),
        ))

        "GetCurrentConnectionIDs" -> soapOk(req.service, req.action, listOf("ConnectionIDs" to "0"))

        "GetCurrentConnectionInfo" -> soapOk(req.service, req.action, listOf(
            "RcsID" to "0",
            "AVTransportID" to "0",
            "ProtocolInfo" to "",
            "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1",
            "Direction" to "Input",
            "Status" to "OK",
        ))

        else -> soapFault(SoapProtocol.ERR_INVALID_ACTION, "Invalid Action")
    }

    // -------------------------------------------------------------------
    // GENA
    // -------------------------------------------------------------------

    private fun handleSubscribe(req: HttpRequest, svc: UpnpService, rt: RendererRuntime, subscriberIp: String): HttpResponse {
        val sid = req.headers["SID"]
        val callback = req.headers["CALLBACK"]
        val nt = req.headers["NT"]
        val timeoutHeader = req.headers["TIMEOUT"]

        if (sid == null && callback != null && nt != null) {
            return when (val result = rt.gena().subscribe(svc, callback, timeoutHeader, subscriberIp)) {
                is GenaSubscriptions.SubscribeResult.Ok -> {
                    val response = HttpResponse(
                        200, "OK",
                        listOf("SID" to result.sub.sid, "TIMEOUT" to "Second-${result.timeoutSeconds}"),
                        "",
                    )
                    rt.onSubscribed(result.sub)
                    response
                }
                GenaSubscriptions.SubscribeResult.BadCallback -> HttpResponse(412, "Precondition Failed", emptyList(), "")
                GenaSubscriptions.SubscribeResult.Full -> HttpResponse(500, "Internal Server Error", emptyList(), "")
                GenaSubscriptions.SubscribeResult.Unknown -> HttpResponse(412, "Precondition Failed", emptyList(), "")
            }
        }

        if (sid != null) {
            return when (val result = rt.gena().renew(sid, timeoutHeader)) {
                is GenaSubscriptions.SubscribeResult.Ok -> HttpResponse(
                    200, "OK",
                    listOf("SID" to result.sub.sid, "TIMEOUT" to "Second-${result.timeoutSeconds}"),
                    "",
                )
                else -> HttpResponse(412, "Precondition Failed", emptyList(), "")
            }
        }

        return HttpResponse(412, "Precondition Failed", emptyList(), "")
    }

    private fun handleUnsubscribe(req: HttpRequest, rt: RendererRuntime): HttpResponse {
        val sid = req.headers["SID"]
        return if (rt.gena().unsubscribe(sid)) HttpResponse(200, "OK", emptyList(), "")
        else HttpResponse(412, "Precondition Failed", emptyList(), "")
    }
}
