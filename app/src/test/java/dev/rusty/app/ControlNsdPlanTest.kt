package dev.rusty.app

import org.junit.Assert.assertEquals
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
}
