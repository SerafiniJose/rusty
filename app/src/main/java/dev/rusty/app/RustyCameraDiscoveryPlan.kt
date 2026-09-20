package dev.rusty.app

data class DiscoveredRustyCamera(
    val deviceId: String,
    val name: String,
    val host: String,
    val rtspPort: Int,
    val apiPort: Int,
    val requiresAuth: Boolean,
) {
    val rtspUrl: String get() = "rtsp://$host:$rtspPort${CameraShareSettings.STREAM_PATH}"
    val snapshotUrl: String get() = CameraShareSettings.snapshotUrl(host, apiPort)
}

/** Pure half of finding other Rusty devices that share a camera (TXT contract: Task 9). */
object RustyCameraDiscoveryPlan {
    fun fromResolved(name: String, host: String?, apiPort: Int, txt: Map<String, String>, ownDeviceId: String): DiscoveredRustyCamera? {
        if (txt["cam"] != "1") return null
        if (apiPort !in 1..65535) return null
        val id = txt["id"]?.takeIf { it.isNotBlank() } ?: return null
        if (id == ownDeviceId) return null
        val h = host?.takeIf { it.isNotBlank() } ?: return null
        val port = txt["rtsp"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return DiscoveredRustyCamera(
            deviceId = id,
            // The raw mDNS instance name can be blank too, same as the TXT name above: without
            // this fallback a peer with both blank yields cam.name == "", a blank row title and
            // (since it is non-null) a blank credentials-dialog title as well.
            name = txt["name"]?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() } ?: "Rusty camera",
            host = h,
            rtspPort = port,
            apiPort = apiPort,
            requiresAuth = txt["auth"] == "1",
        )
    }

    fun merge(found: List<DiscoveredRustyCamera>, known: List<CameraRecord>): List<Pair<DiscoveredRustyCamera, CameraRecord?>> =
        found.distinctBy { it.deviceId }
            .sortedBy { it.name.lowercase() }
            .map { cam -> cam to known.firstOrNull { hostOf(it.rtspUrl) == cam.host } }

    /**
     * The line under a discovered Rusty device's name in the Add-camera scan: always its address,
     * plus whichever of the two things that matters here is true. [added] — the already-added match
     * [merge] paired it with — wins over the password hint, because that row is dimmed and nothing
     * will ever be typed for it; the name this device already knows it by is what tells two
     * identically-named peers apart.
     */
    fun rowSubtitle(cam: DiscoveredRustyCamera, added: CameraRecord?): String = when {
        added != null -> "${cam.host} · ${added.name}"
        cam.requiresAuth -> "${cam.host} · password"
        else -> cam.host
    }
}
