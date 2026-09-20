package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustyCameraDiscoveryPlanTest {
    private val sharing = mapOf("id" to "dev-2", "api" to "1", "name" to "Kitchen", "cam" to "1", "rtsp" to "8554")

    @Test fun `a sharing device resolves to a camera`() {
        val cam = RustyCameraDiscoveryPlan.fromResolved("Kitchen", "192.168.7.20", 8765, sharing, ownDeviceId = "dev-1")!!
        assertEquals("dev-2", cam.deviceId); assertEquals("Kitchen", cam.name); assertEquals("192.168.7.20", cam.host)
        assertEquals(8554, cam.rtspPort); assertEquals(8765, cam.apiPort); assertEquals(false, cam.requiresAuth)
        assertEquals("rtsp://192.168.7.20:8554/live", cam.rtspUrl)
        assertEquals("http://192.168.7.20:8765/api/camera/local/snapshot.jpg", cam.snapshotUrl)
        assertEquals("http://192.168.7.20:9001/api/camera/local/snapshot.jpg", RustyCameraDiscoveryPlan.fromResolved("K", "192.168.7.20", 9001, sharing, "dev-1")!!.snapshotUrl)
    }

    @Test fun `auth flag and custom port are honoured, txt name wins over service name`() {
        val cam = RustyCameraDiscoveryPlan.fromResolved("Kitchen (2)", "10.0.0.9", 8765, sharing + mapOf("auth" to "1", "rtsp" to "9000"), "dev-1")!!
        assertEquals(true, cam.requiresAuth); assertEquals(9000, cam.rtspPort); assertEquals("Kitchen", cam.name)
    }

    @Test fun `not sharing, self, no host, or bad port are dropped`() {
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", "10.0.0.9", 8765, sharing - "cam", "dev-1"))
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", "10.0.0.9", 8765, sharing, ownDeviceId = "dev-2"))
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", null, 8765, sharing, "dev-1"))
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", "10.0.0.9", 8765, sharing + ("rtsp" to "x"), "dev-1"))
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", "10.0.0.9", 0, sharing, "dev-1"))
        assertNull(RustyCameraDiscoveryPlan.fromResolved("A", "10.0.0.9", 8765, sharing - "id", "dev-1"))
    }

    @Test fun `merge dedupes by id, marks already-added hosts, sorts by name`() {
        val a = DiscoveredRustyCamera("dev-2", "Kitchen", "10.0.0.2", 8554, 8765, false)
        val b = DiscoveredRustyCamera("dev-3", "Bedroom", "10.0.0.3", 8554, 8765, true)
        val known = listOf(CameraRecord("cam_1", "Bedroom cam", "rtsp://10.0.0.3:8554/live", null, null, false, true, 0))
        val merged = RustyCameraDiscoveryPlan.merge(listOf(a, a, b), known)
        assertEquals(listOf("Bedroom", "Kitchen"), merged.map { it.first.name })
        assertEquals("cam_1", merged[0].second?.id); assertNull(merged[1].second)

        // Mixed case on purpose: a case-SENSITIVE sort puts "Zone" (Z = 90) before "attic"
        // (a = 97), so this is the pair that actually pins the .lowercase() in sortedBy.
        val lower = DiscoveredRustyCamera("dev-4", "attic", "10.0.0.4", 8554, 8765, false)
        val upper = DiscoveredRustyCamera("dev-5", "Zone", "10.0.0.5", 8554, 8765, false)
        assertEquals(
            listOf("attic", "Zone"),
            RustyCameraDiscoveryPlan.merge(listOf(upper, lower), emptyList()).map { it.first.name },
        )
    }

    @Test fun `blank txt name and blank service name still fall back to a nonblank camera name`() {
        val cam = RustyCameraDiscoveryPlan.fromResolved("", "10.0.0.9", 8765, sharing + ("name" to ""), "dev-1")!!
        assertEquals("Rusty camera", cam.name)
    }

    @Test fun `row subtitle names an added camera, else flags that a password is needed`() {
        val open = DiscoveredRustyCamera("dev-2", "Kitchen", "10.0.0.2", 8554, 8765, false)
        val locked = open.copy(requiresAuth = true)
        val known = CameraRecord("cam_1", "Kitchen cam", "rtsp://10.0.0.2:8554/live", null, null, false, true, 0)
        assertEquals("10.0.0.2", RustyCameraDiscoveryPlan.rowSubtitle(open, null))
        assertEquals("10.0.0.2 · password", RustyCameraDiscoveryPlan.rowSubtitle(locked, null))
        // Already added wins over the password hint: that row is dimmed, so nothing is ever typed
        // for it and the name Rusty already knows it by is the useful thing to show instead.
        assertEquals("10.0.0.2 · Kitchen cam", RustyCameraDiscoveryPlan.rowSubtitle(locked, known))
    }
}
