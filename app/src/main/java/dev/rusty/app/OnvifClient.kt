package dev.rusty.app

import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** One SOAP POST. Implementations do the I/O only; all protocol logic lives in [OnvifClient]. */
fun interface SoapTransport {
    fun post(url: String, body: String, headers: Map<String, String>): SoapResponse
}

/** A SOAP reply. [headers] keys are matched case-insensitively by the client. */
data class SoapResponse(val code: Int, val body: String, val headers: Map<String, String>)

/** A media profile advertised by the camera, as far as stream selection cares. */
data class OnvifProfile(
    val token: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
)

/** Outcome of resolving a camera's stream URI. */
sealed interface OnvifResult {
    data class Resolved(val streamUri: String, val snapshotUri: String?) : OnvifResult

    /** [step] is one of `services`, `profiles`, `streamUri`, `auth`. */
    data class Failed(val step: String, val detail: String) : OnvifResult
}

/**
 * Pure ONVIF SOAP protocol helpers: envelope building, the two authentication schemes cameras
 * ask for (WS-UsernameToken in the SOAP header, HTTP Digest on the wire), and response parsing.
 *
 * Parsing matches element *local* names only — vendor prefixes and namespace URIs vary wildly
 * between firmwares, and several devices reply with the wrong namespace entirely.
 *
 * Nothing here logs: credentials flow through these functions.
 */
object OnvifSoap {

    private const val WSSE_NS =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val WSU_NS =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val PASSWORD_DIGEST_TYPE =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val BASE64_ENCODING_TYPE =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"

    private const val MEDIA10_NS = "http://www.onvif.org/ver10/media/wsdl"
    private const val MEDIA20_NS = "http://www.onvif.org/ver20/media/wsdl"

    /** Highest video height we will ask a camera for — 1080p is the ceiling our players handle. */
    private const val MAX_HEIGHT = 1080

    /** Above this frame rate a stream is decoded less reliably, so it loses same-height ties. */
    private const val PREFERRED_MAX_FRAME_RATE = 30

    /**
     * Builds the `<wsse:Security>` header carrying a WS-UsernameToken with a PasswordDigest:
     * `Base64(SHA1(nonce + created + password))`, with [nonce] hashed as raw bytes and
     * [created]/password as UTF-8 (ONVIF Core Spec / WS-Security UsernameToken Profile 1.0).
     */
    fun wsUsernameToken(username: String, password: String, nonce: ByteArray, created: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        val digest = Base64.getEncoder().encodeToString(sha1.digest())
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        return """
            <wsse:Security xmlns:wsse="$WSSE_NS" xmlns:wsu="$WSU_NS">
              <wsse:UsernameToken>
                <wsse:Username>${escape(username)}</wsse:Username>
                <wsse:Password Type="$PASSWORD_DIGEST_TYPE">$digest</wsse:Password>
                <wsse:Nonce EncodingType="$BASE64_ENCODING_TYPE">$nonceB64</wsse:Nonce>
                <wsu:Created>$created</wsu:Created>
              </wsse:UsernameToken>
            </wsse:Security>
        """.trimIndent()
    }

    /**
     * Builds an HTTP `Authorization: Digest ...` value answering [challenge] (the device's
     * `WWW-Authenticate` header). MD5 with `qop="auth"`: `HA1 = MD5(user:realm:pass)`,
     * `HA2 = MD5(method:uri)`, `response = MD5(HA1:nonce:nc:cnonce:qop:HA2)` with `nc=00000001`.
     * A challenge without `qop` falls back to the RFC 2069 form `MD5(HA1:nonce:HA2)`.
     */
    fun httpDigestAuthorization(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: String,
        cnonce: String,
    ): String {
        val params = parseChallenge(challenge)
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val opaque = params["opaque"]
        val qop = params["qop"]
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it == "auth" }
        val nc = "00000001"

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val response = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        val parts = mutableListOf(
            """username="${quotedHeaderValue(username)}"""",
            """realm="$realm"""",
            """nonce="$nonce"""",
            """uri="$uri"""",
            """response="$response"""",
            "algorithm=MD5",
        )
        if (qop != null) {
            parts += "qop=$qop"
            parts += "nc=$nc"
            parts += """cnonce="$cnonce""""
        }
        if (opaque != null) parts += """opaque="$opaque""""
        return "Digest " + parts.joinToString(", ")
    }

    /** Returns the media service XAddr from a `GetServicesResponse`, or null when absent. */
    fun parseServices(xml: String): String? {
        return try {
            parseServicesInternal(xml)
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the video profiles out of a `GetProfilesResponse`; a broken body yields an empty list. */
    fun parseProfiles(xml: String): List<OnvifProfile> {
        return try {
            parseProfilesInternal(xml)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returns the `<Uri>` of a `GetStreamUriResponse` / `GetSnapshotUriResponse`. */
    fun parseUri(xml: String): String? {
        return try {
            firstText(xml, "Uri")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the device's UTC clock from a `GetSystemDateAndTimeResponse` as an ISO-8601 stamp
     * (`2010-09-16T03:04:05Z`), or null when the device didn't report a usable UTC time. Only
     * `UTCDateTime` is read — `LocalDateTime` carries the device's local zone and would sign
     * requests with a skewed timestamp.
     */
    fun parseDeviceTime(xml: String): String? {
        return try {
            parseDeviceTimeInternal(xml)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Picks the profile to stream: the tallest H264 profile no taller than 1080p, preferring a
     * frame rate of 30 or less when two are the same height. H265 is considered only when
     * [hevcAllowed] and no H264 profile qualifies — an eligible H264 always wins.
     */
    fun selectProfile(profiles: List<OnvifProfile>, hevcAllowed: Boolean = false): OnvifProfile? {
        val eligible = profiles.filter { it.height in 1..MAX_HEIGHT }
        val order = compareBy<OnvifProfile>(
            { it.height },
            { if (it.frameRate in 1..PREFERRED_MAX_FRAME_RATE) 1 else 0 },
        )
        eligible.filter { isCodec(it.codec, "H264") }.maxWithOrNull(order)?.let { return it }
        if (!hevcAllowed) return null
        return eligible.filter { isCodec(it.codec, "H265") }.maxWithOrNull(order)
    }

    private fun isCodec(codec: String, wanted: String): Boolean {
        val normalized = codec.uppercase(Locale.US).replace(".", "").replace("-", "")
        val alias = if (wanted == "H265") "HEVC" else "AVC"
        return normalized == wanted || normalized == alias
    }

    // --- envelopes ----------------------------------------------------------------------------

    /** Wraps [bodyXml] in a SOAP 1.2 envelope, with [securityHeader] in the header when present. */
    fun envelope(bodyXml: String, securityHeader: String?): String {
        val header = if (securityHeader == null) "" else "<s:Header>$securityHeader</s:Header>"
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="$MEDIA10_NS" xmlns:tt="http://www.onvif.org/ver10/schema">$header<s:Body>$bodyXml</s:Body></s:Envelope>"""
    }

    fun getSystemDateAndTimeBody(): String = "<tds:GetSystemDateAndTime/>"

    fun getServicesBody(): String = "<tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>"

    fun getProfilesBody(): String = "<trt:GetProfiles/>"

    fun getStreamUriBody(profileToken: String): String =
        "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
            "<trt:ProfileToken>${escape(profileToken)}</trt:ProfileToken></trt:GetStreamUri>"

    fun getSnapshotUriBody(profileToken: String): String =
        "<trt:GetSnapshotUri><trt:ProfileToken>${escape(profileToken)}</trt:ProfileToken></trt:GetSnapshotUri>"

    // --- internals ----------------------------------------------------------------------------

    private fun parseChallenge(challenge: String): Map<String, String> {
        val body = challenge.trim().removePrefix("Digest").removePrefix("digest").trim()
        val params = mutableMapOf<String, String>()
        // key=value or key="value", comma separated; values may contain commas inside quotes.
        val regex = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
        for (match in regex.findAll(body)) {
            val key = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        return params
    }

    /**
     * Renders a value inside a quoted `key="value"` header param: CR/LF (header injection) and
     * other control characters are dropped, backslash and quote are escaped per RFC 7230's
     * quoted-string rules. Only the *rendering* is escaped — the digest hash always uses the raw
     * value, as the RFC requires.
     */
    private fun quotedHeaderValue(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when {
                c == '\r' || c == '\n' -> Unit
                c.code < 0x20 || c.code == 0x7F -> Unit
                c == '\\' || c == '"' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun newParser(xml: String): XmlPullParser {
        val parser: XmlPullParser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun parseServicesInternal(xml: String): String? {
        val parser = newParser(xml)
        var namespace: String? = null
        var xaddr: String? = null
        var media10: String? = null
        var media20: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Service" -> {
                        namespace = null
                        xaddr = null
                    }
                    "Namespace" -> namespace = readText(parser)?.trim()
                    "XAddr" -> xaddr = readText(parser)?.trim()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "Service") {
                val ns = namespace
                val addr = xaddr
                if (ns != null && !addr.isNullOrEmpty()) {
                    if (ns == MEDIA10_NS && media10 == null) media10 = addr
                    if (ns == MEDIA20_NS && media20 == null) media20 = addr
                }
                namespace = null
                xaddr = null
            }
            event = parser.next()
        }
        return media10 ?: media20
    }

    /**
     * Fields are scoped per `<Profiles>` element and the resolution/codec are only read while
     * inside that profile's `<VideoEncoderConfiguration>`, so a profile's audio or metadata
     * configuration can never contribute a stray Width/Height.
     */
    private fun parseProfilesInternal(xml: String): List<OnvifProfile> {
        val parser = newParser(xml)
        val profiles = mutableListOf<OnvifProfile>()

        var token: String? = null
        var inVideoEncoder = false
        var codec: String? = null
        var width = 0
        var height = 0
        var frameRate = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Profiles", "Profile" -> {
                        token = attribute(parser, "token")
                        inVideoEncoder = false
                        codec = null
                        width = 0
                        height = 0
                        frameRate = 0
                    }
                    "VideoEncoderConfiguration", "VideoEncoder2Configuration" -> inVideoEncoder = true
                    "Encoding" -> if (inVideoEncoder) codec = readText(parser)?.trim()
                    "Width" -> if (inVideoEncoder) width = readInt(parser)
                    "Height" -> if (inVideoEncoder) height = readInt(parser)
                    "FrameRateLimit", "FrameRate" -> if (inVideoEncoder && frameRate == 0) {
                        frameRate = readInt(parser)
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "VideoEncoderConfiguration", "VideoEncoder2Configuration" -> inVideoEncoder = false
                    "Profiles", "Profile" -> {
                        val t = token
                        val c = codec
                        if (t != null && c != null) {
                            profiles += OnvifProfile(t, c, width, height, frameRate)
                        }
                        token = null
                        codec = null
                    }
                }
            }
            event = parser.next()
        }
        return profiles
    }

    private fun parseDeviceTimeInternal(xml: String): String? {
        val parser = newParser(xml)
        var inUtc = false
        var inTime = false
        var inDate = false
        var hour = -1
        var minute = -1
        var second = -1
        var year = -1
        var month = -1
        var day = -1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "UTCDateTime" -> inUtc = true
                    "Time" -> if (inUtc) inTime = true
                    "Date" -> if (inUtc) inDate = true
                    "Hour" -> if (inTime) hour = readInt(parser)
                    "Minute" -> if (inTime) minute = readInt(parser)
                    "Second" -> if (inTime) second = readInt(parser)
                    "Year" -> if (inDate) year = readInt(parser)
                    "Month" -> if (inDate) month = readInt(parser)
                    "Day" -> if (inDate) day = readInt(parser)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "UTCDateTime" -> inUtc = false
                    "Time" -> inTime = false
                    "Date" -> inDate = false
                }
            }
            event = parser.next()
        }

        if (year < 0 || month < 0 || day < 0 || hour < 0 || minute < 0 || second < 0) return null
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02dZ", year, month, day, hour, minute, second)
    }

    private fun firstText(xml: String, localName: String): String? {
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == localName) {
                return readText(parser)
            }
            event = parser.next()
        }
        return null
    }

    private fun attribute(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun readInt(parser: XmlPullParser): Int = readText(parser)?.trim()?.toDoubleOrNull()?.toInt() ?: 0

    /** Reads the text content of the current start-tag element and advances past its end-tag. */
    private fun readText(parser: XmlPullParser): String? {
        if (parser.isEmptyElementTag) return null
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_TAG) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString()
    }
}

/**
 * Talks ONVIF to one camera over an injected [transport], resolving its RTSP stream URI (and a
 * snapshot URI when the device offers one).
 *
 * Authentication: every call after the clock probe carries a WS-UsernameToken signed with the
 * *device's* clock (cameras reject tokens outside a few seconds of their own time, and consumer
 * cameras are routinely minutes off). Devices that instead want HTTP Digest answer 401 with a
 * challenge; the same call is then retried once with an `Authorization` header. A second 401 is
 * a credentials problem, not a scheme problem, and fails with step `auth`.
 *
 * Redirects are followed manually so a camera can never bounce credentials at another host: only
 * same host:port redirects are followed, at most [MAX_REDIRECTS] per logical call.
 *
 * Nothing in this class logs — the request bodies contain a password digest and the headers a
 * digest response.
 */
class OnvifClient(
    private val transport: SoapTransport,
    private val nonceSource: () -> ByteArray,
    private val hevcAllowed: Boolean = false,
    private val cnonceSource: () -> String = { defaultCnonce() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private sealed interface CallOutcome {
        data class Ok(val body: String) : CallOutcome
        data class Fail(val failure: OnvifResult.Failed) : CallOutcome
    }

    /** Runs the full ONVIF sequence against the device service at [deviceXAddr]. */
    fun resolve(deviceXAddr: String, username: String, password: String): OnvifResult {
        val created = deviceCreated(deviceXAddr)

        val servicesXml = when (val outcome = authedCall(deviceXAddr, OnvifSoap.getServicesBody(), username, password, created, "services")) {
            is CallOutcome.Fail -> return outcome.failure
            is CallOutcome.Ok -> outcome.body
        }
        val advertisedMedia = OnvifSoap.parseServices(servicesXml)
            ?: return OnvifResult.Failed("services", "no ONVIF media service advertised")
        // Cameras behind NAT routinely advertise a stale or internal address here, and a hostile
        // reply could point the credentialed media calls at another host entirely — so only the
        // path is taken from the advertisement; the host we talk to stays the one the user configured.
        val mediaXAddr = pinToDeviceHost(deviceXAddr, advertisedMedia)

        val profilesXml = when (val outcome = authedCall(mediaXAddr, OnvifSoap.getProfilesBody(), username, password, created, "profiles")) {
            is CallOutcome.Fail -> return outcome.failure
            is CallOutcome.Ok -> outcome.body
        }
        val profiles = OnvifSoap.parseProfiles(profilesXml)
        if (profiles.isEmpty()) return OnvifResult.Failed("profiles", "no media profiles in the reply")
        val profile = OnvifSoap.selectProfile(profiles, hevcAllowed)
            ?: return OnvifResult.Failed("profiles", "no compatible H264 profile at 1080p or below")

        val streamXml = when (
            val outcome = authedCall(mediaXAddr, OnvifSoap.getStreamUriBody(profile.token), username, password, created, "streamUri")
        ) {
            is CallOutcome.Fail -> return outcome.failure
            is CallOutcome.Ok -> outcome.body
        }
        val streamUri = OnvifSoap.parseUri(streamXml)
            ?: return OnvifResult.Failed("streamUri", "no stream URI in the reply")

        // Best effort: plenty of cameras have no snapshot endpoint at all.
        val snapshotUri = when (
            val outcome = authedCall(mediaXAddr, OnvifSoap.getSnapshotUriBody(profile.token), username, password, created, "streamUri")
        ) {
            is CallOutcome.Fail -> null
            is CallOutcome.Ok -> OnvifSoap.parseUri(outcome.body)
        }

        return OnvifResult.Resolved(streamUri, snapshotUri)
    }

    /**
     * Fetches the device clock unauthenticated (it is the one ONVIF call that must work without
     * credentials, precisely so the token can be timestamped). Any failure falls back to our own
     * wall clock rather than aborting: a device with a correct clock still authenticates fine.
     */
    private fun deviceCreated(deviceXAddr: String): String {
        val body = OnvifSoap.envelope(OnvifSoap.getSystemDateAndTimeBody(), null)
        val response = try {
            transport.post(deviceXAddr, body, baseHeaders())
        } catch (_: Exception) {
            null
        }
        val deviceTime = response?.takeIf { it.code == 200 }?.let { OnvifSoap.parseDeviceTime(it.body) }
        return deviceTime ?: utcNow()
    }

    /**
     * Sends one logical authenticated call, handling redirects and a single HTTP Digest retry.
     * [step] labels the failure this call produces; a rejected retry always fails as `auth`.
     */
    private fun authedCall(
        url: String,
        bodyXml: String,
        username: String,
        password: String,
        created: String,
        step: String,
    ): CallOutcome {
        var target = url
        var redirects = 0
        var authorization: String? = null
        var digestTried = false

        while (true) {
            val header = OnvifSoap.wsUsernameToken(username, password, nonceSource(), created)
            val envelope = OnvifSoap.envelope(bodyXml, header)
            val headers = baseHeaders() + (authorization?.let { mapOf("Authorization" to it) } ?: emptyMap())

            val response = try {
                transport.post(target, envelope, headers)
            } catch (e: Exception) {
                return CallOutcome.Fail(OnvifResult.Failed(step, "request failed: ${e.javaClass.simpleName}"))
            }

            when {
                response.code == 200 -> return CallOutcome.Ok(response.body)

                response.code == 401 -> {
                    if (digestTried) {
                        return CallOutcome.Fail(OnvifResult.Failed("auth", "camera rejected the credentials"))
                    }
                    val challenge = headerValue(response.headers, "WWW-Authenticate")
                        ?: return CallOutcome.Fail(OnvifResult.Failed("auth", "camera rejected the credentials"))
                    if (!challenge.trim().startsWith("Digest", ignoreCase = true)) {
                        return CallOutcome.Fail(OnvifResult.Failed("auth", "unsupported authentication scheme"))
                    }
                    digestTried = true
                    authorization = OnvifSoap.httpDigestAuthorization(
                        username = username,
                        password = password,
                        method = "POST",
                        uri = pathOf(target),
                        challenge = challenge,
                        cnonce = cnonceSource(),
                    )
                }

                response.code in REDIRECT_CODES -> {
                    val location = headerValue(response.headers, "Location")
                        ?: return CallOutcome.Fail(OnvifResult.Failed(step, "redirect without a Location header"))
                    val next = absolute(target, location)
                        ?: return CallOutcome.Fail(OnvifResult.Failed(step, "unusable redirect target"))
                    if (!sameHost(target, next)) {
                        // Never replay credentials at a host the user didn't configure.
                        return CallOutcome.Fail(OnvifResult.Failed(step, "refused a cross-host redirect"))
                    }
                    if (redirects >= MAX_REDIRECTS) {
                        return CallOutcome.Fail(OnvifResult.Failed(step, "too many redirects"))
                    }
                    redirects++
                    target = next
                    // A digest response is bound to the request URI, so re-challenge on the new one.
                    authorization = null
                    digestTried = false
                }

                else -> return CallOutcome.Fail(OnvifResult.Failed(step, "HTTP ${response.code}"))
            }
        }
    }

    /**
     * Returns [advertised] when it names the same host:port as [deviceXAddr]; otherwise the
     * device's own scheme+authority with the advertised path (and query). Ports count as part of
     * the authority, so a redirected port is rewritten too.
     */
    private fun pinToDeviceHost(deviceXAddr: String, advertised: String): String {
        if (sameHost(deviceXAddr, advertised)) return advertised
        val origin = deviceXAddr.substringBefore("://") + "://" +
            deviceXAddr.substringAfter("://").substringBefore('/')
        val path = pathOf(advertised).let { if (it.startsWith("/")) it else "/$it" }
        return origin + path
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/soap+xml; charset=utf-8",
    )

    private fun utcNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(clock()))
    }

    private companion object {
        const val MAX_REDIRECTS = 2
        val REDIRECT_CODES = setOf(301, 302, 307, 308)

        fun defaultCnonce(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /** Request-URI for the digest hash: path plus query, defaulting to `/`. */
        fun pathOf(url: String): String {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) return url
            val slash = afterScheme.indexOf('/')
            return if (slash < 0) "/" else afterScheme.substring(slash)
        }

        fun absolute(base: String, location: String): String? {
            val trimmed = location.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.contains("://")) return trimmed
            val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
            return if (trimmed.startsWith("/")) origin + trimmed else "$origin/$trimmed"
        }

        /** Same host AND port — a redirect to another port is another server for our purposes. */
        fun sameHost(a: String, b: String): Boolean {
            val ha = authorityOf(a) ?: return false
            val hb = authorityOf(b) ?: return false
            return ha == hb
        }

        fun authorityOf(url: String): String? {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) return null
            val scheme = url.substringBefore("://").lowercase(Locale.US)
            val authority = afterScheme.substringBefore('/').lowercase(Locale.US)
            val hostPort = authority.substringAfterLast('@')
            return if (hostPort.contains(':')) hostPort else {
                val port = if (scheme == "https") "443" else "80"
                "$hostPort:$port"
            }
        }
    }
}

/**
 * The real transport: one `HttpURLConnection` POST per call, with redirects left to
 * [OnvifClient] so credentials are never auto-replayed at another host. Thin I/O — untested.
 */
class AndroidSoapTransport : SoapTransport {

    override fun post(url: String, body: String, headers: Map<String, String>): SoapResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            for ((name, value) in headers) connection.setRequestProperty(name, value)

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
                .mapKeys { (key, _) -> key!! }
            SoapResponse(code, text, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
