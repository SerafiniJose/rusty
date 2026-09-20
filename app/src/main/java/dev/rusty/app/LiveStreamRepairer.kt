package dev.rusty.app

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The real [StreamRepairer]: sniff once ([RtspParamSetFetcher]), remember the parameter sets per
 * stream URL for the life of the process, and serve media3 through an [SdpRepairProxy]. One proxy
 * at a time — a live session plays one stream — replaced on every new repair/cached start and
 * closed by [close]. Pure JVM apart from the [shared] instance's logger.
 */
open class LiveStreamRepairer(
    private val fetch: (url: String, user: String?, pass: String?) -> RtspParamSetFetcher.Result = { u, us, p -> RtspParamSetFetcher().fetch(u, us, p) },
    private val proxyFactory: (host: String, port: Int, sps: ByteArray, pps: ByteArray) -> SdpRepairProxy = { h, p, s, pp -> SdpRepairProxy(h, p, s, pp) },
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sdp-repair").apply { isDaemon = true } },
    private val log: (String) -> Unit = {},
) : StreamRepairer {

    private class ParamSets(val sps: ByteArray, val pps: ByteArray)

    private val cache = ConcurrentHashMap<String, ParamSets>()
    private var proxy: SdpRepairProxy? = null
    private val lock = Any()
    /** Bumped by every proxy start and every [close]; guarded by [lock]. See [repair]. */
    private var generation = 0

    override fun cachedProxyUrl(url: String): String? {
        val sets = cache[url] ?: return null
        return (startProxy(url, sets) as? StartOutcome.Started)?.proxyUrl
    }

    override fun repair(url: String, user: String?, pass: String?, forceTcp: Boolean, onResult: (RepairResult) -> Unit) {
        // Captured at SUBMIT time: any close() or proxy start after this point supersedes the
        // result, however long the sniff takes. Without it a view closed mid-sniff would still
        // spawn an unowned proxy, or a later session's proxy would be torn down by an older one.
        val gen = synchronized(lock) { generation }
        executor.execute {
            val result = try {
                // forceTcp is accepted for interface symmetry only: the sniff and the proxy are
                // always interleaved TCP.
                var failure: String? = null
                val sets = cache[url] ?: when (val r = fetch(url, user, pass)) {
                    is RtspParamSetFetcher.Result.Found ->
                        ParamSets(r.sps, r.pps).also { cache[url] = it; log("repair: learned SPS/PPS for ${CameraUri.redact(url)}") }
                    is RtspParamSetFetcher.Result.Failed -> {
                        failure = r.reason
                        // The fetcher authors the reason; strip any URI userinfo it embedded
                        // before it reaches a log, exactly as CameraPlayback does with it.
                        log("repair: sniff failed: ${PlaybackErrorText.stripUserinfo(r.reason)}")
                        null
                    }
                }
                when (sets) {
                    null -> RepairResult.Failed(failure ?: "sniff failed")
                    else -> synchronized(lock) {
                        if (gen != generation) RepairResult.Failed("superseded")
                        else when (val started = startProxy(url, sets)) {
                            is StartOutcome.Started -> RepairResult.Ready(started.proxyUrl)
                            // A URL we cannot even parse is a configuration problem, not a busy
                            // loopback port: keep the two distinguishable in the log.
                            is StartOutcome.BadUrl -> RepairResult.Failed("bad url")
                            is StartOutcome.BindFailed -> RepairResult.Failed("proxy bind failed")
                        }
                    }
                }
            } catch (t: Throwable) {
                // The classification only: a sniff failure's message folds in the credentialed URI.
                RepairResult.Failed(t.javaClass.simpleName)
            }
            onResult(result)
        }
    }

    override fun invalidate(url: String) { cache.remove(url) }

    override fun close() {
        synchronized(lock) { generation += 1; proxy?.close(); proxy = null }
    }

    /** What one [startProxy] attempt did: bound a proxy, or failed for a reason the caller must
     *  be able to tell apart. */
    private sealed class StartOutcome {
        data class Started(val proxyUrl: String) : StartOutcome()
        /** The stream URL does not parse, or names no host — nothing to proxy to. */
        object BadUrl : StartOutcome()
        /** The proxy could not listen on a loopback port. */
        object BindFailed : StartOutcome()
    }

    /** Replaces the current proxy. Bumps [generation]: an in-flight repair that submitted before
     *  this call must not touch the proxy this starts. Locks are reentrant, so the call from
     *  [repair] (already under [lock]) is fine. */
    private fun startProxy(url: String, sets: ParamSets): StartOutcome {
        val uri = runCatching { URI(url) }.getOrNull() ?: return StartOutcome.BadUrl
        val host = uri.host ?: return StartOutcome.BadUrl
        val port = if (uri.port > 0) uri.port else 554
        synchronized(lock) {
            generation += 1
            proxy?.close()
            proxy = null
            val p = proxyFactory(host, port, sets.sps, sets.pps)
            val bound = p.start()
            if (bound == null) {
                // Nothing is listening, so nothing owns it; close it rather than leave the object
                // (and, for a real proxy that half-bound, its socket) behind.
                runCatching { p.close() }
                return StartOutcome.BindFailed
            }
            proxy = p
            return StartOutcome.Started(loopbackUrl(uri, bound))
        }
    }

    companion object {
        /** [url] with its scheme/host/port replaced by the loopback proxy's; path and query kept
         *  verbatim (the camera matches on the path) and any userinfo dropped. Null when [url]
         *  does not parse — callers treat that as a bad url, never as a proxy failure. */
        fun proxyUrlFor(url: String, port: Int): String? =
            runCatching { URI(url) }.getOrNull()?.let { loopbackUrl(it, port) }

        private fun loopbackUrl(uri: URI, port: Int): String {
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "rtsp://127.0.0.1:$port$path$query"
        }

        /** Process-wide instance so the SPS/PPS cache outlives one live view. */
        val shared: LiveStreamRepairer by lazy { LiveStreamRepairer(log = { android.util.Log.i("CameraPlayback", it) }) }
    }
}
