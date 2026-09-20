package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdpRepairTest {
    /** Verbatim from the Reolink CX820 (firmware 2024.12.11) SUB stream, 2026-09-10. */
    private val reolinkSub = listOf(
        "v=0", "o=- 1694370000 1 IN IP4 192.168.4.90", "s=Session streamed by \"preview\"", "i=h264Preview_01_sub",
        "t=0 0", "a=tool:Reolink Streaming Media 2024.12.11", "a=type:broadcast", "a=control:*", "a=range:npt=now-",
        "a=x-qt-text-nam:Session streamed by \"preview\"",
        "m=video 0 RTP/AVP 96", "c=IN IP4 0.0.0.0", "b=AS:500", "a=rtpmap:96 H264/90000", "a=range:npt=now-", "a=control:track1",
        "m=audio 0 RTP/AVP 97", "c=IN IP4 0.0.0.0", "b=AS:256", "a=rtpmap:97 MPEG4-GENERIC/16000",
        "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408",
        "a=recvonly", "a=control:track2",
    ).joinToString("\r\n", postfix = "\r\n")

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0x15)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())

    @Test
    fun `detects the H264 video track without fmtp`() {
        assertEquals(96, SdpRepair.missingH264Fmtp(reolinkSub))
    }

    @Test
    fun `a track whose fmtp carries sprop needs nothing`() {
        val fixed = reolinkSub.replace("a=rtpmap:96 H264/90000\r\n", "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\n")
        assertNull(SdpRepair.missingH264Fmtp(fixed))
    }

    @Test
    fun `an fmtp without sprop is completed in place`() {
        // media3 reports this shape as "missing sprop parameter": complete the line, never add a second one.
        val partial = reolinkSub.replace("a=rtpmap:96 H264/90000\r\n", "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033\r\n")
        assertEquals(96, SdpRepair.missingH264Fmtp(partial))
        val out = SdpRepair.inject(partial, 96, sps, pps)
        assertEquals(
            partial.replace("a=fmtp:96 packetization-mode=1;profile-level-id=640033\r\n", "a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\n"),
            out,
        )
        assertNull(SdpRepair.missingH264Fmtp(out))
    }

    @Test
    fun `payload types are scoped per section so only the deficient section is touched`() {
        val good = "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\na=control:track1\r\n"
        val bad = "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track3\r\n"
        val two = "v=0\r\n$good$bad"
        assertEquals(96, SdpRepair.missingH264Fmtp(two))
        val out = SdpRepair.inject(two, 96, sps, pps)
        val badFixed = bad.replace("a=rtpmap:96 H264/90000\r\n", "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\n")
        assertEquals("v=0\r\n$good$badFixed", out)
    }

    @Test
    fun `audio only and non-H264 video need nothing`() {
        assertNull(SdpRepair.missingH264Fmtp("v=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n"))
        assertNull(SdpRepair.missingH264Fmtp("v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\n"))
    }

    @Test
    fun `inject puts the fmtp line right after the rtpmap and touches nothing else`() {
        val out = SdpRepair.inject(reolinkSub, 96, sps, pps)
        val expected = reolinkSub.replace(
            "a=rtpmap:96 H264/90000\r\n",
            "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\n",
        )
        assertEquals(expected, out)
        assertNull(SdpRepair.missingH264Fmtp(out))
    }

    @Test
    fun `inject preserves bare LF endings`() {
        val lf = "v=0\nm=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\na=control:track1\n"
        val out = SdpRepair.inject(lf, 96, sps, pps)
        assertEquals("v=0\nm=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==\na=control:track1\n", out)
    }

    @Test
    fun `inject with an unknown payload type is a no-op`() {
        assertEquals(reolinkSub, SdpRepair.inject(reolinkSub, 98, sps, pps))
    }

    @Test
    fun `inject strips an Annex-B start code before encoding`() {
        val withStart = byteArrayOf(0, 0, 0, 1) + sps
        val out = SdpRepair.fmtpLine(96, withStart, pps)
        assertEquals("a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==", out)
    }

    @Test
    fun `profile level id falls back for a truncated sps`() {
        assertEquals("42001e", SdpRepair.profileLevelId(byteArrayOf(0x67, 0x64)))
    }

    @Test
    fun `an overlong payload type in rtpmap does not throw`() {
        // Malformed/oversized text straight off the wire (Task 8's DESCRIBE body) must never throw;
        // a payload type that overflows Int is treated as no match, so nothing here needs repair.
        val sdp = "v=0\r\nm=video 0 RTP/AVP 999999999999999999999\r\na=rtpmap:999999999999999999999 H264/90000\r\na=control:track1\r\n"
        assertNull(SdpRepair.missingH264Fmtp(sdp))
        assertEquals(sdp, SdpRepair.inject(sdp, 96, sps, pps))
    }

    @Test
    fun `an empty SDP or one with no m lines needs nothing`() {
        assertNull(SdpRepair.missingH264Fmtp(""))
        assertEquals("", SdpRepair.inject("", 96, sps, pps))
        val noMedia = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\nt=0 0\r\n"
        assertNull(SdpRepair.missingH264Fmtp(noMedia))
        assertEquals(noMedia, SdpRepair.inject(noMedia, 96, sps, pps))
    }

    @Test
    fun `a sibling payload type's fmtp in the same section is not cross-matched`() {
        // pt 96 has no fmtp of its own; the section's a=fmtp:97 line belongs to a different payload
        // type and must not be read as satisfying pt 96's missing fmtp.
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96 97\r\na=rtpmap:96 H264/90000\r\na=fmtp:97 profile-level-id=1\r\na=control:track1\r\n"
        assertEquals(96, SdpRepair.missingH264Fmtp(sdp))
        val out = SdpRepair.inject(sdp, 96, sps, pps)
        assertEquals(
            sdp.replace(
                "a=rtpmap:96 H264/90000\r\n",
                "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA==\r\n",
            ),
            out,
        )
    }
}
