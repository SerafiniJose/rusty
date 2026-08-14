package dev.rusty.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeHost(val name: String = "host") : PanelControlHost {
    val shown = mutableListOf<ControlPanelId>()
    var themeChanges = 0
    var backgroundCalls = 0

    override fun showPanel(id: ControlPanelId) {
        shown.add(id)
    }

    override fun onLockscreenThemeChanged() {
        themeChanges++
    }

    override fun sendToBackground() {
        backgroundCalls++
    }
}

class PanelControlRelayTest {

    @Before fun setUp() = PanelControlRelay.resetForTest()
    @After fun tearDown() = PanelControlRelay.resetForTest()

    // ---- detached ----------------------------------------------------------

    @Test fun `detached relay reports no host, no panel, and refuses requests`() {
        assertFalse(PanelControlRelay.hasHost())
        assertNull(PanelControlRelay.current())
        assertFalse(PanelControlRelay.requestPanel(ControlPanelId.DLNA))
    }

    /** A theme notification with no window is not a failure — the preference is already saved,
     *  and the next saver to mount reads it. */
    @Test fun `theme notification with no host is a silent no-op`() {
        PanelControlRelay.notifyLockscreenThemeChanged()
        assertFalse(PanelControlRelay.hasHost())
    }

    // ---- attached ----------------------------------------------------------

    @Test fun `attach seeds the showing panel`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.HOME_ASSISTANT)
        assertTrue(PanelControlRelay.hasHost())
        assertEquals(ControlPanelId.HOME_ASSISTANT, PanelControlRelay.current())
    }

    @Test fun `requestPanel forwards to the host and reports acceptance`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.SPOTIFY)
        assertTrue(PanelControlRelay.requestPanel(ControlPanelId.LOCKSCREEN))
        assertEquals(listOf(ControlPanelId.LOCKSCREEN), host.shown)
    }

    /**
     * Acceptance is not application: the relay must keep reporting the OLD panel until the shell
     * publishes the new one. This is what makes the control page's pending lamp honest — an
     * optimistic update here would light the lamp for a switch that never happened.
     */
    @Test fun `requestPanel does not move current by itself`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.SPOTIFY)
        PanelControlRelay.requestPanel(ControlPanelId.DLNA)
        assertEquals(ControlPanelId.SPOTIFY, PanelControlRelay.current())

        PanelControlRelay.publishCurrent(ControlPanelId.DLNA)
        assertEquals(ControlPanelId.DLNA, PanelControlRelay.current())
    }

    @Test fun `requestBackground forwards to the host`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.SPOTIFY)
        assertTrue(PanelControlRelay.requestBackground())
        assertEquals(1, host.backgroundCalls)
    }

    /** No host means Rusty is already not in front — the caller treats false as "nothing to do",
     *  not as a failure, so this must simply report that rather than throw. */
    @Test fun `requestBackground with no host reports false`() {
        assertFalse(PanelControlRelay.requestBackground())
    }

    @Test fun `theme notification reaches an attached host`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.SPOTIFY)
        PanelControlRelay.notifyLockscreenThemeChanged()
        assertEquals(1, host.themeChanges)
    }

    // ---- detach ------------------------------------------------------------

    @Test fun `detach clears both the host and the reported panel`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.DLNA)
        PanelControlRelay.detachHost(host)

        assertFalse(PanelControlRelay.hasHost())
        assertNull(PanelControlRelay.current())
        assertFalse(PanelControlRelay.requestPanel(ControlPanelId.SPOTIFY))
        assertTrue(host.shown.isEmpty())
    }

    /** An edge arriving after onPause must not resurrect a panel the API would then report as
     *  live and commandable. */
    @Test fun `publishCurrent while detached is ignored`() {
        val host = FakeHost()
        PanelControlRelay.attachHost(host, ControlPanelId.SPOTIFY)
        PanelControlRelay.detachHost(host)
        PanelControlRelay.publishCurrent(ControlPanelId.DLNA)
        assertNull(PanelControlRelay.current())
    }

    // ---- overlapping Activity recreation -----------------------------------

    /**
     * During a recreation the incoming Activity's onResume runs BEFORE the outgoing one's onPause.
     * The newer window must win, and the older one's detach must not unregister it.
     */
    @Test fun `a second attach replaces the first and the stale detach is ignored`() {
        val outgoing = FakeHost("outgoing")
        val incoming = FakeHost("incoming")

        PanelControlRelay.attachHost(outgoing, ControlPanelId.SPOTIFY)
        PanelControlRelay.attachHost(incoming, ControlPanelId.DLNA)
        PanelControlRelay.detachHost(outgoing)

        assertTrue(PanelControlRelay.hasHost())
        assertEquals(ControlPanelId.DLNA, PanelControlRelay.current())

        assertTrue(PanelControlRelay.requestPanel(ControlPanelId.LOCKSCREEN))
        assertEquals(listOf(ControlPanelId.LOCKSCREEN), incoming.shown)
        assertTrue(outgoing.shown.isEmpty())
    }

    @Test fun `detaching the live host after a replace does clear it`() {
        val outgoing = FakeHost("outgoing")
        val incoming = FakeHost("incoming")

        PanelControlRelay.attachHost(outgoing, ControlPanelId.SPOTIFY)
        PanelControlRelay.attachHost(incoming, ControlPanelId.DLNA)
        PanelControlRelay.detachHost(incoming)

        assertFalse(PanelControlRelay.hasHost())
        assertNull(PanelControlRelay.current())
    }

    // ---- re-attach ---------------------------------------------------------

    @Test fun `a fresh window re-attaching restores command and reporting`() {
        val first = FakeHost("first")
        PanelControlRelay.attachHost(first, ControlPanelId.SPOTIFY)
        PanelControlRelay.detachHost(first)

        val second = FakeHost("second")
        PanelControlRelay.attachHost(second, ControlPanelId.HOME_ASSISTANT)

        assertEquals(ControlPanelId.HOME_ASSISTANT, PanelControlRelay.current())
        assertTrue(PanelControlRelay.requestPanel(ControlPanelId.SPOTIFY))
        assertEquals(listOf(ControlPanelId.SPOTIFY), second.shown)
    }
}
