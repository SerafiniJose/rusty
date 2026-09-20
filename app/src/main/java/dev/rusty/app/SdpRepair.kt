package dev.rusty.app

import java.util.Base64

/**
 * Repairs an SDP whose H.264 video track has no `a=fmtp` line. media3's RTSP stack refuses such a
 * track ("missing attribute fmtp") even though the camera sends SPS/PPS in-band; the line it wants
 * is built from those in-band parameter sets, exactly as [RtspProtocol.sdp] builds ours.
 * Pure text: no `android.*`, no sockets.
 */
object SdpRepair {
    private val RTPMAP = Regex("^a=rtpmap:(\\d+)\\s+H264/", RegexOption.IGNORE_CASE)

    /** The section that needs work: its payload type, the index of its `a=rtpmap` line, and the
     *  index of an existing `a=fmtp` line that merely lacks `sprop-parameter-sets` (null = none). */
    private data class Target(val pt: Int, val rtpmapAt: Int, val fmtpAt: Int?)

    /** Payload types are scoped to their `m=` section (RFC 4566 §5.14), so the search is per
     *  section: the first `m=video` whose rtpmap is H264 and whose fmtp is absent OR has no
     *  `sprop-parameter-sets`. */
    private fun findTarget(lines: List<String>): Target? {
        var inVideo = false
        var pt: Int? = null
        var rtpmapAt = -1
        var fmtpAt: Int? = null
        var hasSprop = false
        fun current(): Target? {
            val p = pt
            return if (inVideo && p != null && rtpmapAt >= 0 && !hasSprop) Target(p, rtpmapAt, fmtpAt) else null
        }
        for ((i, line) in lines.withIndex()) {
            if (line.startsWith("m=")) {
                current()?.let { return it }
                inVideo = line.startsWith("m=video"); pt = null; rtpmapAt = -1; fmtpAt = null; hasSprop = false
                continue
            }
            if (!inVideo) continue
            // toIntOrNull, not toInt: a malformed or oversized payload-type digit string (this text
            // comes straight off the wire in Task 8) must be treated as "no match", never throw.
            RTPMAP.find(line)?.let { m -> m.groupValues[1].toIntOrNull()?.let { n -> pt = n; rtpmapAt = i } }
            val p = pt ?: continue
            if (line.startsWith("a=fmtp:$p ") || line == "a=fmtp:$p") {
                fmtpAt = i
                hasSprop = line.contains("sprop-parameter-sets=", ignoreCase = true)
            }
        }
        return current()
    }

    fun missingH264Fmtp(sdp: String): Int? = findTarget(lines(sdp))?.pt

    fun inject(sdp: String, payloadType: Int, sps: ByteArray, pps: ByteArray): String {
        val ls = lines(sdp)
        val t = findTarget(ls) ?: return sdp
        if (t.pt != payloadType) return sdp
        val spsNal = H264Nal.splitAnnexB(sps).firstOrNull() ?: sps
        val ppsNal = H264Nal.splitAnnexB(pps).firstOrNull() ?: pps
        val b64 = Base64.getEncoder()
        val sprop = "sprop-parameter-sets=${b64.encodeToString(spsNal)},${b64.encodeToString(ppsNal)}"
        val out = ls.toMutableList()
        val fmtpAt = t.fmtpAt
        if (fmtpAt != null) {
            // Complete the camera's own line ("missing sprop parameter" case), keep its parameters.
            val line = out[fmtpAt].trimEnd(';')
            out[fmtpAt] = if (line == "a=fmtp:$payloadType") "$line $sprop" else "$line;$sprop"
        } else {
            out.add(t.rtpmapAt + 1, fmtpLine(payloadType, spsNal, ppsNal))
        }
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        // Keep a missing final newline missing.
        return out.joinToString(eol) + if (sdp.endsWith("\n")) eol else ""
    }

    fun fmtpLine(payloadType: Int, sps: ByteArray, pps: ByteArray): String {
        val spsNal = H264Nal.splitAnnexB(sps).firstOrNull() ?: sps
        val ppsNal = H264Nal.splitAnnexB(pps).firstOrNull() ?: pps
        val b64 = Base64.getEncoder()
        return "a=fmtp:$payloadType packetization-mode=1;profile-level-id=${profileLevelId(spsNal)};sprop-parameter-sets=${b64.encodeToString(spsNal)},${b64.encodeToString(ppsNal)}"
    }

    fun profileLevelId(sps: ByteArray): String =
        if (sps.size >= 4) "%02x%02x%02x".format(sps[1], sps[2], sps[3]) else "42001e"

    /** Lines without their terminators; a trailing terminator does not produce an empty last line. */
    private fun lines(sdp: String): List<String> {
        val raw = sdp.split("\r\n", "\n")
        return if (raw.isNotEmpty() && raw.last().isEmpty()) raw.dropLast(1) else raw
    }
}
