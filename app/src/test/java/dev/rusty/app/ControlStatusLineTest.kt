package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure decision behind the General settings row's status line, extracted out of
 * [SettingsSheet] for the same reason [ControlNsdPlan] was: mapping [ControlServerStatus.State]
 * to a string is an ordinary decision with no `android.*` dependency, and it is the piece of this
 * task most likely to render wrong — [ControlServerStatus.State.Running] with an empty url is a
 * real, expected state (bound, no site-local IPv4 yet) that must never show as blank or as a
 * broken `http://:8765`.
 */
class ControlStatusLineTest {

    @Test
    fun `stopped shows Off`() {
        assertEquals("Off", ControlStatusLine.text(ControlServerStatus.State.Stopped))
    }

    @Test
    fun `starting shows an ellipsis`() {
        assertEquals("Starting…", ControlStatusLine.text(ControlServerStatus.State.Starting))
    }

    @Test
    fun `running with a url shows the url`() {
        assertEquals(
            "http://192.168.1.9:8765",
            ControlStatusLine.text(ControlServerStatus.State.Running("http://192.168.1.9:8765")),
        )
    }

    @Test
    fun `running with no url yet — bound before Wi-Fi, never blank or a broken host-less url`() {
        assertEquals(
            "Waiting for network…",
            ControlStatusLine.text(ControlServerStatus.State.Running("")),
        )
    }

    @Test
    fun `failed shows the message`() {
        assertEquals(
            "Couldn't start: port in use",
            ControlStatusLine.text(ControlServerStatus.State.Failed("Couldn't start: port in use")),
        )
    }
}
