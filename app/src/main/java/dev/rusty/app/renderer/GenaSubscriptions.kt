package dev.rusty.app.renderer

import java.net.URI

/**
 * Pure, in-memory GENA subscription table (spec Architecture: eventing). Time and SID
 * generation are injected so the table is deterministic and testable off-device; the
 * HTTP server layer is responsible for actually sending NOTIFY requests using the
 * sequence numbers and callback URLs handed back here.
 */
class GenaSubscriptions(private val nowMs: () -> Long, private val newSid: () -> String) {
    data class Sub(
        val sid: String,
        val service: UpnpService,
        val callbackUrl: String,
        val expiresAtMs: Long,
        val seq: Long,
    )

    private class MutableSub(
        val sid: String,
        val service: UpnpService,
        val callbackUrl: String,
        var expiresAtMs: Long,
        var seq: Long = 0,
        var failures: Int = 0,
    )

    sealed class SubscribeResult {
        data class Ok(val sub: Sub, val timeoutSeconds: Int, val isInitial: Boolean) : SubscribeResult()
        object BadCallback : SubscribeResult()
        object Full : SubscribeResult()
        object Unknown : SubscribeResult()
    }

    private val table = LinkedHashMap<String, MutableSub>()

    companion object {
        private const val MIN_TIMEOUT_SECONDS = 300
        private const val MAX_TIMEOUT_SECONDS = 1800
        private const val DEFAULT_TIMEOUT_SECONDS = 1800
        private const val MAX_SUBSCRIPTIONS = 8
        private const val MAX_CONSECUTIVE_FAILURES = 2

        private fun parseTimeoutSeconds(header: String?): Int {
            val n = header?.removePrefix("Second-")?.toIntOrNull() ?: return DEFAULT_TIMEOUT_SECONDS
            return n.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        }

        private fun parseCallback(header: String?, subscriberIp: String): String? {
            val trimmed = header?.trim() ?: return null
            if (!trimmed.startsWith('<') || !trimmed.endsWith('>')) return null
            val url = trimmed.substring(1, trimmed.length - 1)
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.host != subscriberIp) return null
            return url
        }

        private fun toSub(m: MutableSub): Sub = Sub(m.sid, m.service, m.callbackUrl, m.expiresAtMs, m.seq)
    }

    /** Removes entries whose lease has expired. Must be called with the lock held. */
    private fun pruneExpiredLocked() {
        val now = nowMs()
        val it = table.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs <= now) it.remove()
        }
    }

    fun subscribe(service: UpnpService, callbackHeader: String?, timeoutHeader: String?, subscriberIp: String): SubscribeResult {
        val callbackUrl = parseCallback(callbackHeader, subscriberIp) ?: return SubscribeResult.BadCallback
        synchronized(this) {
            pruneExpiredLocked()
            if (table.size >= MAX_SUBSCRIPTIONS) return SubscribeResult.Full
            val timeoutSeconds = parseTimeoutSeconds(timeoutHeader)
            val sid = newSid()
            val sub = MutableSub(sid, service, callbackUrl, nowMs() + timeoutSeconds * 1000L)
            table[sid] = sub
            return SubscribeResult.Ok(toSub(sub), timeoutSeconds, isInitial = true)
        }
    }

    fun renew(sid: String?, timeoutHeader: String?): SubscribeResult {
        if (sid == null) return SubscribeResult.Unknown
        synchronized(this) {
            pruneExpiredLocked()
            val sub = table[sid] ?: return SubscribeResult.Unknown
            val timeoutSeconds = parseTimeoutSeconds(timeoutHeader)
            sub.expiresAtMs = nowMs() + timeoutSeconds * 1000L
            return SubscribeResult.Ok(toSub(sub), timeoutSeconds, isInitial = false)
        }
    }

    fun unsubscribe(sid: String?): Boolean {
        if (sid == null) return false
        synchronized(this) {
            return table.remove(sid) != null
        }
    }

    fun activeFor(service: UpnpService): List<Sub> {
        synchronized(this) {
            pruneExpiredLocked()
            return table.values.filter { it.service == service }.map { toSub(it) }
        }
    }

    fun nextSeq(sid: String): Long {
        synchronized(this) {
            val sub = table[sid] ?: return 0L
            val current = sub.seq
            sub.seq += 1
            return current
        }
    }

    fun markFailed(sid: String) {
        synchronized(this) {
            val sub = table[sid] ?: return
            sub.failures += 1
            if (sub.failures >= MAX_CONSECUTIVE_FAILURES) table.remove(sid)
        }
    }

    fun clear() {
        synchronized(this) {
            table.clear()
        }
    }
}
