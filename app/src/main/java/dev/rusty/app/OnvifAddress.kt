package dev.rusty.app

/** Turns what a user types into the "Camera address" field into device-service XAddrs to try. */
object OnvifAddress {
    val DEFAULT_PORTS = listOf(8000, 80)
    private const val PATH = "/onvif/device_service"
    private val HOST = Regex("""^(\[[0-9a-fA-F:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::(\d{1,5}))?$""")

    fun candidates(input: String): List<String> {
        var s = input.trim()
        if (s.isEmpty()) return emptyList()
        if (s.contains("://")) s = s.substringAfter("://").substringBefore('/')
        s = s.substringAfterLast('@')
        val m = HOST.matchEntire(s) ?: return emptyList()
        val host = m.groupValues[1]
        val port = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (port != null && port !in 1..65535) return emptyList()
        val ports = if (port != null) listOf(port) else DEFAULT_PORTS
        return ports.map { "http://$host:$it$PATH" }
    }
}
