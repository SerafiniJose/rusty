package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenaSubscriptionsTest {
    private var now = 1_000_000L
    private var sidN = 0
    private fun table() = GenaSubscriptions({ now }, { "uuid:sid-${++sidN}" })

    @Test fun subscribe_acceptsMatchingCallbackAndNegotiatesTimeout() {
        val t = table()
        val r = t.subscribe(UpnpService.AVTRANSPORT, "<http://192.168.7.2:39201/notify>", "Second-300", "192.168.7.2")
        val ok = r as GenaSubscriptions.SubscribeResult.Ok
        assertEquals("uuid:sid-1", ok.sub.sid); assertEquals(300, ok.timeoutSeconds); assertTrue(ok.isInitial)
        assertEquals(0L, t.nextSeq(ok.sub.sid))   // initial NOTIFY is SEQ 0
        assertEquals(1L, t.nextSeq(ok.sub.sid))
    }

    @Test fun subscribe_rejectsForeignCallbackHost() {
        val r = table().subscribe(UpnpService.AVTRANSPORT, "<http://8.8.8.8:80/x>", null, "192.168.7.2")
        assertTrue(r is GenaSubscriptions.SubscribeResult.BadCallback)
    }

    @Test fun renew_extendsAndExpiryPrunes() {
        val t = table()
        val ok = t.subscribe(UpnpService.RENDERINGCONTROL, "<http://10.0.0.2:1/x>", "Second-300", "10.0.0.2")
            as GenaSubscriptions.SubscribeResult.Ok
        now += 200_000
        assertTrue(t.renew(ok.sub.sid, "Second-300") is GenaSubscriptions.SubscribeResult.Ok)
        now += 301_000
        assertTrue(t.activeFor(UpnpService.RENDERINGCONTROL).isEmpty())
        assertTrue(t.renew(ok.sub.sid, null) is GenaSubscriptions.SubscribeResult.Unknown)
    }

    @Test fun capAndFailureDrop() {
        val t = table()
        repeat(8) { i ->
            t.subscribe(UpnpService.AVTRANSPORT, "<http://10.0.0.2:$i/x>", null, "10.0.0.2")
        }
        assertTrue(t.subscribe(UpnpService.AVTRANSPORT, "<http://10.0.0.2:99/x>", null, "10.0.0.2")
            is GenaSubscriptions.SubscribeResult.Full)
        val sid = t.activeFor(UpnpService.AVTRANSPORT).first().sid
        t.markFailed(sid); t.markFailed(sid)
        assertTrue(t.activeFor(UpnpService.AVTRANSPORT).none { it.sid == sid })
    }
}
