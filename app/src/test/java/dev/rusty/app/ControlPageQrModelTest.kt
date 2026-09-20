package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Control page row in the Remote Control tab: address text and whether Show QR can open. */
class ControlPageQrModelTest {

    @Test fun running_showsTheUrlAndOffersTheQr() {
        val row = ControlPageQrModel.row(ControlServerStatus.State.Running("http://192.168.7.251:8765"))
        assertEquals("http://192.168.7.251:8765", row.address)
        assertEquals("http://192.168.7.251:8765", row.url)
        assertTrue(row.qrEnabled)
    }

    /** Bound but no routable LAN address yet: the server publishes an empty URL. */
    @Test fun runningWithoutAnAddress_waitsForNetwork() {
        val row = ControlPageQrModel.row(ControlServerStatus.State.Running(""))
        assertEquals("Waiting for network", row.address)
        assertNull(row.url)
        assertFalse(row.qrEnabled)
    }

    @Test fun starting_saysSo() {
        val row = ControlPageQrModel.row(ControlServerStatus.State.Starting)
        assertEquals("Starting the server…", row.address)
        assertFalse(row.qrEnabled)
    }

    @Test fun stopped_saysSo() {
        val row = ControlPageQrModel.row(ControlServerStatus.State.Stopped)
        assertEquals("Not running", row.address)
        assertFalse(row.qrEnabled)
    }

    @Test fun failed_carriesTheMessage() {
        val row = ControlPageQrModel.row(ControlServerStatus.State.Failed("port 8765 in use"))
        assertEquals("Not running — port 8765 in use", row.address)
        assertFalse(row.qrEnabled)
    }
}
