package dev.rusty.app.renderer

import java.net.URI

data class SsdpIdentity(
    val udn: String,
    val location: String,
    val bootId: Long,
    val configId: Long,
)
data class SsdpMSearch(val st: String, val mxSeconds: Int)

object SsdpMessages {
    const val SERVER_HEADER = "Android UPnP/1.0 Rusty/2.1"
    const val MAX_AGE_SECONDS = 1800

    /** Wildcard bind addresses: valid to LISTEN on, never a destination a control point can GET. */
    private val UNROUTABLE_HOSTS = setOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")

    /** Host of the LOCATION URL, or null when it is blank/unparseable. IPv6 literals come back from
     *  [URI] in their bracketed form (`[::1]`); the brackets are URL syntax, not part of the host. */
    fun locationHost(id: SsdpIdentity): String? =
        runCatching { URI(id.location).host }.getOrNull()
            ?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotBlank() }

    /**
     * Whether this identity may be put on the wire at all. An advertisement is a PROMISE that
     * LOCATION can be fetched: the service's "no address yet" sentinel (0.0.0.0 — the wildcard bind
     * address, which every renderer has even with no network) is not a routable destination. A
     * control point that receives such an alive burst registers the renderer as present, fails the
     * GET, and caches that broken device for max-age (1800 s) — strictly worse than staying silent
     * until a real address exists.
     */
    fun isAdvertisable(id: SsdpIdentity): Boolean {
        if (id.udn.isBlank()) return false
        val host = locationHost(id) ?: return false
        return host !in UNROUTABLE_HOSTS
    }

    fun parseMSearch(packet: String): SsdpMSearch? {
        val lines = packet.split("\r\n")
        if (lines.firstOrNull()?.startsWith("M-SEARCH * HTTP/1.1") != true) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
        }.toMap()
        if (headers["MAN"]?.trim('"') != "ssdp:discover") return null
        val st = headers["ST"] ?: return null
        val mx = headers["MX"]?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        return SsdpMSearch(st, mx)
    }

    /** The 6 (NT, USN) advertisement pairs, in root → uuid → device → services order. */
    fun advertisementTargets(id: SsdpIdentity): List<Pair<String, String>> = buildList {
        add("upnp:rootdevice" to "${id.udn}::upnp:rootdevice")
        add(id.udn to id.udn)
        add("urn:schemas-upnp-org:device:MediaRenderer:1" to
            "${id.udn}::urn:schemas-upnp-org:device:MediaRenderer:1")
        for (svc in UpnpService.values()) add(svc.serviceType to "${id.udn}::${svc.serviceType}")
    }

    fun responsesFor(search: SsdpMSearch, id: SsdpIdentity): List<String> {
        val targets = advertisementTargets(id)
        val matched = when (search.st) {
            "ssdp:all" -> targets
            else -> targets.filter { it.first == search.st }
        }
        return matched.map { (st, usn) ->
            "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n" +
            "EXT:\r\n" +
            "LOCATION: ${id.location}\r\n" +
            "SERVER: $SERVER_HEADER\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: ${id.bootId}\r\n" +
            "CONFIGID.UPNP.ORG: ${id.configId}\r\n" +
            "\r\n"
        }
    }

    fun aliveNotifications(id: SsdpIdentity): List<String> =
        advertisementTargets(id).map { (nt, usn) ->
            "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n" +
            "LOCATION: ${id.location}\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:alive\r\n" +
            "SERVER: $SERVER_HEADER\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: ${id.bootId}\r\n" +
            "CONFIGID.UPNP.ORG: ${id.configId}\r\n" +
            "\r\n"
        }

    fun byebyeNotifications(id: SsdpIdentity): List<String> =
        advertisementTargets(id).map { (nt, usn) ->
            "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: ${id.bootId}\r\n" +
            "CONFIGID.UPNP.ORG: ${id.configId}\r\n" +
            "\r\n"
        }
}
