package dev.rusty.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads this device's Wi-Fi link as three numbers, or null when the active network is not Wi-Fi.
 *
 * Only link speed, band and RSSI are read: none of them is location-gated (SSID and BSSID are,
 * and are never touched). `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` are already in the
 * manifest for the multicast lock. Not unit-tested (no Robolectric in this project) — every
 * decision made from the numbers lives in [CameraShareSettingsModel.networkAdvice].
 */
object WifiLinkProbe {
    fun read(context: Context): CameraShareSettingsModel.WifiLink? = runCatching {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info: WifiInfo = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? WifiInfo else null)
            ?: @Suppress("DEPRECATION") (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            ?: return null
        // The sharer UPLOADS, so the transmit rate is the one that matters; it only exists on R+.
        val tx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.txLinkSpeedMbps > 0) info.txLinkSpeedMbps else info.linkSpeed
        if (tx <= 0) return null
        CameraShareSettingsModel.WifiLink(txMbps = tx, is5GHz = info.frequency >= 4_900, rssiDbm = info.rssi)
    }.getOrNull()
}
