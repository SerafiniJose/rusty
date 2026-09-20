package dev.rusty.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Registers the pair of connectivity callbacks every LAN server in this app needs.
 *
 * Why two and not one: the LAN is not always the DEFAULT network. With a VPN up the default is the
 * tunnel (whose CGNAT address is not site-local), and a Wi-Fi network Android could not validate —
 * a LAN with no internet — never becomes default at all. The default callback then only ever
 * reports links with no site-local IPv4, so a server that came up before Wi-Fi did would never
 * learn its address. That is the NORMAL case for these services: `BOOT_COMPLETED` fires before
 * Wi-Fi associates. So the LAN transports are watched directly — no INTERNET capability is
 * required, deliberately.
 *
 * Neither registration is allowed to take the service down with it: each is wrapped and logged.
 *
 * [ControlService] and [dev.rusty.app.renderer.MediaRendererService] solve exactly this problem
 * (an advertised URL, an SSDP LOCATION) and had a transcribed copy each.
 */
object LanNetworkCallbacks {

    /** Registers [default] for the default network and [lan] for Wi-Fi/Ethernet. [tag] is the
     *  caller's logcat tag, so a failure is attributed to the service that owns the callbacks. */
    fun register(
        cm: ConnectivityManager,
        tag: String,
        default: ConnectivityManager.NetworkCallback,
        lan: ConnectivityManager.NetworkCallback,
    ) {
        runCatching { cm.registerDefaultNetworkCallback(default) }
            .onFailure { Log.w(tag, "Failed to register default-network callback", it) }
        runCatching {
            val lanRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(lanRequest, lan)
        }.onFailure { Log.w(tag, "Failed to register LAN network callback", it) }
    }
}
