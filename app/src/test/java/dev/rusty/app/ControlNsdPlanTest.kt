package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind NSD advertisement, extracted out of `ControlNsdAdvertiser`/
 * `ControlService` for the reason `ControlRuntimeMath` and `MediaRendererController.shouldRun`
 * were: `NsdManager` cannot run on the JVM, but "register, re-register or leave it alone" and
 * "what goes in the TXT record" are ordinary decisions that should be pinned by tests rather
 * than by inspection.
 */
class ControlNsdPlanTest {

    // -- action(previous, new) --------------------------------------------------------------

    @Test
    fun `nothing was advertised and there is still nothing to advertise — no-op`() {
        assertEquals(ControlNsdPlan.Action.NoOp, ControlNsdPlan.action("", ""))
    }

    @Test
    fun `an address just arrived — register`() {
        assertEquals(
            ControlNsdPlan.Action.Register,
            ControlNsdPlan.action("", "http://192.168.1.9:8765"),
        )
    }

    @Test
    fun `the address was lost — unregister`() {
        assertEquals(
            ControlNsdPlan.Action.Unregister,
            ControlNsdPlan.action("http://192.168.1.9:8765", ""),
        )
    }

    @Test
    fun `the address changed to a different one — register again`() {
        assertEquals(
            ControlNsdPlan.Action.Register,
            ControlNsdPlan.action("http://192.168.1.9:8765", "http://192.168.1.42:8765"),
        )
    }

    @Test
    fun `the same url twice in a row — no-op, do not churn on every network callback tick`() {
        assertEquals(
            ControlNsdPlan.Action.NoOp,
            ControlNsdPlan.action("http://192.168.1.9:8765", "http://192.168.1.9:8765"),
        )
    }

    // -- txtAttributes -----------------------------------------------------------------------

    @Test
    fun `TXT carries id, api and name — the wire contract the HA integration keys on`() {
        val attrs = ControlNsdPlan.txtAttributes(deviceId = "abc-123", deviceName = "Rusty Speaker")
        assertEquals(
            mapOf("id" to "abc-123", "api" to "1", "name" to "Rusty Speaker"),
            attrs,
        )
    }

    @Test
    fun `a device that is not sharing its camera advertises exactly what it always did`() {
        assertEquals(
            mapOf("id" to "dev-1", "api" to "1", "name" to "Kitchen"),
            ControlNsdPlan.txtAttributes("dev-1", "Kitchen"),
        )
    }

    @Test
    fun `a shared camera adds cam and the fixed rtsp port`() {
        val txt = ControlNsdPlan.txtAttributes("dev-1", "Kitchen", cameraShared = true)
        assertEquals("1", txt["cam"])
        assertEquals("8554", txt["rtsp"])
    }

    @Test
    fun `a shared camera behind a password adds auth`() {
        val txt = ControlNsdPlan.txtAttributes("dev-1", "Kitchen", cameraShared = true, authRequired = true)
        assertEquals("1", txt["auth"])
    }

    @Test
    fun `an open share never claims a password it does not enforce`() {
        val txt = ControlNsdPlan.txtAttributes("dev-1", "Kitchen", cameraShared = true, authRequired = false)
        assertEquals(null, txt["auth"])
    }

    @Test
    fun `auth cannot appear without a camera — it describes the stream, not the API`() {
        val txt = ControlNsdPlan.txtAttributes("dev-1", "Kitchen", cameraShared = false, authRequired = true)
        assertEquals(null, txt["auth"])
        assertEquals(null, txt["cam"])
    }

    // -- advertisesCamera ----------------------------------------------------------------------

    @Test
    fun `a share that has never been turned on advertises no camera`() {
        assertFalse(ControlNsdPlan.advertisesCamera(CameraShareStatus.State.Off))
    }

    @Test
    fun `a device that cannot share a camera advertises none`() {
        assertFalse(ControlNsdPlan.advertisesCamera(CameraShareStatus.State.Unsupported("no camera")))
    }

    @Test
    fun `an unavailable share advertises no camera — the TXT record omits the camera key`() {
        assertFalse(ControlNsdPlan.advertisesCamera(CameraShareStatus.State.Unavailable("camera permission needed")))
    }

    @Test
    fun `a share that is up and idle advertises a camera`() {
        assertTrue(ControlNsdPlan.advertisesCamera(CameraShareStatus.State.Ready))
    }

    @Test
    fun `a share with viewers attached advertises a camera`() {
        assertTrue(ControlNsdPlan.advertisesCamera(CameraShareStatus.State.Streaming(2)))
    }

    // -- advertKey -----------------------------------------------------------------------------

    @Test
    fun `the camera being shared changes the key even though the url did not — register`() {
        val before = ControlNsdPlan.advertKey("http://10.0.0.5:8765", ControlNsdPlan.txtAttributes("d", "n"))
        val after = ControlNsdPlan.advertKey(
            "http://10.0.0.5:8765",
            ControlNsdPlan.txtAttributes("d", "n", cameraShared = true),
        )
        assertEquals(ControlNsdPlan.Action.Register, ControlNsdPlan.action(before, after))
    }

    @Test
    fun `the same url and the same txt fold to the same key — no-op`() {
        val key = ControlNsdPlan.advertKey("http://10.0.0.5:8765", ControlNsdPlan.txtAttributes("d", "n"))
        assertEquals(ControlNsdPlan.Action.NoOp, ControlNsdPlan.action(key, key))
    }

    @Test
    fun `no url folds to the empty key whatever the txt record says`() {
        assertEquals("", ControlNsdPlan.advertKey("", ControlNsdPlan.txtAttributes("d", "n")))
    }

    @Test
    fun `losing the address unregisters even a share that was advertising a camera`() {
        val shared = ControlNsdPlan.advertKey(
            "http://10.0.0.5:8765",
            ControlNsdPlan.txtAttributes("d", "n", cameraShared = true),
        )
        assertEquals(
            ControlNsdPlan.Action.Unregister,
            ControlNsdPlan.action(shared, ControlNsdPlan.advertKey("", emptyMap())),
        )
    }

    @Test
    fun `the same attributes in a different map order fold to the same key — never a false change`() {
        val txt = ControlNsdPlan.txtAttributes("d", "n", cameraShared = true, authRequired = true)
        assertEquals(
            ControlNsdPlan.advertKey("http://10.0.0.5:8765", txt),
            ControlNsdPlan.advertKey("http://10.0.0.5:8765", txt.entries.reversed().associate { it.key to it.value }),
        )
    }
}
