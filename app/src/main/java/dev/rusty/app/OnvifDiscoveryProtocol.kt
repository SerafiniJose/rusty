package dev.rusty.app

import java.io.StringReader
import java.net.URLDecoder
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * A camera discovered via ONVIF WS-Discovery.
 *
 * [xaddrs] preserves the order the device advertised them in; [name] and [hardware] come from
 * the `onvif://www.onvif.org/name/...` and `.../hardware/...` scopes and are absent (null) when
 * the device didn't publish that scope.
 */
data class DiscoveredCamera(
    val endpointUuid: String,
    val xaddrs: List<String>,
    val name: String?,
    val hardware: String?,
)

/**
 * Pure WS-Discovery protocol logic for ONVIF camera discovery: building the multicast Probe
 * message and parsing ProbeMatch replies. No sockets here — see the discovery client (Task 4)
 * for the UDP transport that wraps this.
 */
object OnvifDiscoveryProtocol {

    private const val SOAP_ENVELOPE_NS = "http://www.w3.org/2003/05/soap-envelope"
    private const val WSA_NS = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
    private const val WSD_NS = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    private const val WSD_TO = "urn:schemas-xmlsoap-org:ws:2005:04:discovery"
    private const val WSD_PROBE_ACTION = "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"
    private const val ONVIF_NETWORK_NS = "http://www.onvif.org/ver10/network/wsdl"
    private const val NAME_SCOPE_PREFIX = "onvif://www.onvif.org/name/"
    private const val HARDWARE_SCOPE_PREFIX = "onvif://www.onvif.org/hardware/"

    /**
     * Builds a SOAP 1.2 WS-Discovery Probe envelope (UTF-8 bytes) probing for ONVIF network
     * video transmitters (cameras). [messageUuid] becomes the WS-Addressing MessageID.
     */
    fun buildProbe(messageUuid: String): ByteArray {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="$SOAP_ENVELOPE_NS" xmlns:wsa="$WSA_NS" xmlns:wsd="$WSD_NS" xmlns:dn="$ONVIF_NETWORK_NS">
              <soap:Header>
                <wsa:Action>$WSD_PROBE_ACTION</wsa:Action>
                <wsa:MessageID>urn:uuid:$messageUuid</wsa:MessageID>
                <wsa:To>$WSD_TO</wsa:To>
              </soap:Header>
              <soap:Body>
                <wsd:Probe>
                  <wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>
                </wsd:Probe>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        return xml.toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses a WS-Discovery reply, returning the [DiscoveredCamera] described by its
     * ProbeMatch, or null when the envelope has no ProbeMatch (e.g. a Hello) or fails to parse.
     */
    fun parseProbeMatch(xml: String): DiscoveredCamera? {
        return try {
            parseProbeMatchInternal(xml)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Removes duplicate replies for the same endpoint (multiple network interfaces or repeated
     * multicast delivery commonly produce these), keeping the first occurrence of each uuid.
     */
    fun dedupe(replies: List<DiscoveredCamera>): List<DiscoveredCamera> {
        val seen = LinkedHashMap<String, DiscoveredCamera>()
        for (reply in replies) {
            seen.putIfAbsent(reply.endpointUuid, reply)
        }
        return seen.values.toList()
    }

    /**
     * Picks the best XAddr to connect to from a device's advertised list: an IPv4 http(s) URL
     * on the caller's own subnet is preferred, falling back to the first IPv4 URL found;
     * IPv6-only lists (or an empty list) return null.
     */
    fun pickXAddr(xaddrs: List<String>, localAddress: String, prefixLength: Int): String? {
        val localOctets = parseIpv4(localAddress)

        var firstIpv4: String? = null
        for (xaddr in xaddrs) {
            if (!xaddr.startsWith("http://") && !xaddr.startsWith("https://")) continue
            val host = hostOf(xaddr) ?: continue
            val octets = parseIpv4(host) ?: continue // skip IPv6 / unparsable hosts
            if (firstIpv4 == null) firstIpv4 = xaddr
            if (localOctets != null && sameSubnet(octets, localOctets, prefixLength)) {
                return xaddr
            }
        }
        return firstIpv4
    }

    /**
     * Fields are scoped to a single `<ProbeMatch>` element: [endpointUuid]/[scopes]/[xaddrsText]
     * are reset when a new ProbeMatch starts, so a multi-match envelope (one device replying on
     * several interfaces, or a batch of matches) can never pair one match's uuid with another
     * match's xaddrs/scopes.
     */
    private fun parseProbeMatchInternal(xml: String): DiscoveredCamera? {
        val parser: XmlPullParser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var inProbeMatch = false
        var endpointUuid: String? = null
        var scopes: String? = null
        var xaddrsText: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ProbeMatch" -> {
                        inProbeMatch = true
                        endpointUuid = null
                        scopes = null
                        xaddrsText = null
                    }
                    "Address" -> if (inProbeMatch && endpointUuid == null) {
                        endpointUuid = readText(parser)?.trim()
                    }
                    "Scopes" -> if (inProbeMatch) {
                        scopes = readText(parser)
                    }
                    "XAddrs" -> if (inProbeMatch) {
                        xaddrsText = readText(parser)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "ProbeMatch") {
                inProbeMatch = false
                val uuid = endpointUuid
                if (uuid != null) {
                    // Complete match found — return the first one, never mixing fields from a
                    // later <ProbeMatch> in the same envelope.
                    val xaddrs = xaddrsText?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
                    val (name, hardware) = parseScopes(scopes)
                    return DiscoveredCamera(endpointUuid = uuid, xaddrs = xaddrs, name = name, hardware = hardware)
                }
            }
            event = parser.next()
        }

        return null
    }

    /** Reads the text content of the current start-tag element and advances past its end-tag. */
    private fun readText(parser: XmlPullParser): String? {
        if (parser.isEmptyElementTag) return null
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG)) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString()
    }

    private fun parseScopes(scopes: String?): Pair<String?, String?> {
        if (scopes == null) return null to null
        var name: String? = null
        var hardware: String? = null
        for (token in scopes.trim().split(Regex("\\s+"))) {
            if (token.isBlank()) continue
            when {
                token.startsWith(NAME_SCOPE_PREFIX) -> name = decode(token.removePrefix(NAME_SCOPE_PREFIX))
                token.startsWith(HARDWARE_SCOPE_PREFIX) -> hardware = decode(token.removePrefix(HARDWARE_SCOPE_PREFIX))
            }
        }
        return name to hardware
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    /** Extracts the host from an http(s) URL by string parsing (no DNS, pure JVM). */
    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null
        if (withoutScheme.startsWith("[")) {
            // IPv6 literal: host is everything inside the brackets.
            val end = withoutScheme.indexOf(']')
            return if (end >= 0) withoutScheme.substring(0, end + 1) else null
        }
        val hostAndPort = withoutScheme.substringBefore('/')
        return hostAndPort.substringBefore(':')
    }

    /** Parses a dotted-quad IPv4 address into its four octets, or null if not IPv4. */
    private fun parseIpv4(host: String): IntArray? {
        if (host.startsWith("[")) return null // IPv6 literal
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            octets[i] = n
        }
        return octets
    }

    private fun sameSubnet(a: IntArray, b: IntArray, prefixLength: Int): Boolean {
        val prefix = prefixLength.coerceIn(0, 32)
        val aInt = toInt(a)
        val bInt = toInt(b)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (aInt and mask) == (bInt and mask)
    }

    private fun toInt(octets: IntArray): Int {
        return (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
    }
}
