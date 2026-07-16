package dev.rusty.app.renderer

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * The single answer to "which LAN address is this device on?", shared by [MediaRendererService]
 * (which puts it in the SSDP LOCATION) and [SsdpEndpoint] (which picks the multicast transmit
 * interface).
 *
 * They used to answer it with two independent rules — ConnectivityManager's DEFAULT network vs. the
 * first up, non-loopback interface with a site-local IPv4 — and those rules disagree whenever the
 * LAN is not the default network: a VPN/tunnel (default network, CGNAT 100.64/10 address, which is
 * NOT site-local) or a Wi-Fi network Android did not validate (a LAN with no internet stays
 * non-default). The renderer then advertised the 0.0.0.0 fallback while SSDP happily transmitted
 * from wlan0. One rule, one answer.
 */
internal object LanAddress {

    private const val TAG = "LanAddress"

    /**
     * The first site-local IPv4 (10/8, 172.16/12, 192.168/16) among [addresses]. Loopback,
     * link-local, IPv6 and CGNAT (100.64/10, what a VPN tunnel hands out) are all rejected: only an
     * address other LAN devices can actually reach may be advertised.
     */
    fun siteLocalIpv4(addresses: List<InetAddress>): Inet4Address? =
        addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }

    /** Up, non-loopback interfaces, in enumeration order. */
    fun usableInterfaces(): List<NetworkInterface> {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .onFailure { Log.w(TAG, "Failed to enumerate network interfaces", it) }
            .getOrNull() ?: return emptyList()
        return interfaces.filter { nif -> runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false) }
    }

    fun addressesOf(nif: NetworkInterface): List<InetAddress> =
        runCatching { Collections.list(nif.inetAddresses) }.getOrDefault(emptyList())

    /** Site-local IPv4 of the first usable interface that has one, or null when there is no LAN. */
    fun siteLocalIpv4(): String? =
        usableInterfaces().firstNotNullOfOrNull { siteLocalIpv4(addressesOf(it)) }?.hostAddress

    /** The usable interface that owns [host], or null when no interface carries that address. */
    fun interfaceOwning(host: String): NetworkInterface? =
        usableInterfaces().firstOrNull { nif -> addressesOf(nif).any { it.hostAddress == host } }
}
