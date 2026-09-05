package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CameraStatusRelayTest {
    @Before
    fun reset() = CameraStatusRelay.resetForTest()

    @Test
    fun `publish replaces the map and notifies only on change`() {
        var fired = 0
        CameraStatusRelay.addListener { fired++ }
        val a = mapOf("cam_1" to CameraStatus(TileState.OK, null))
        CameraStatusRelay.publish(a)
        assertEquals(a, CameraStatusRelay.current())
        assertEquals(1, fired)
        CameraStatusRelay.publish(mapOf("cam_1" to CameraStatus(TileState.OK, null)))
        assertEquals(1, fired)
        CameraStatusRelay.publish(mapOf("cam_1" to CameraStatus(TileState.UNREACHABLE, 42L)))
        assertEquals(2, fired)
        CameraStatusRelay.publish(emptyMap())
        assertEquals(3, fired)
        assertEquals(emptyMap<String, CameraStatus>(), CameraStatusRelay.current())
    }

    @Test
    fun `a removed listener is not called`() {
        var fired = 0
        val l = { fired++; Unit }
        CameraStatusRelay.addListener(l)
        CameraStatusRelay.removeListener(l)
        CameraStatusRelay.publish(mapOf("x" to CameraStatus(TileState.NONE, null)))
        assertEquals(0, fired)
    }
}
