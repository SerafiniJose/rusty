package dev.rusty.app

/** Outcome of one SDP repair attempt. */
sealed class RepairResult {
    /** The loopback proxy is listening; open [proxyUrl] (no userinfo) instead of the camera URL. */
    data class Ready(val proxyUrl: String) : RepairResult()
    /**
     * The repair did not produce a playable proxy. [reason] must be a SHORT, credential-free
     * classification the owner can log as-is ("no sps/pps within deadline", "bind failed",
     * "describe 401") — never a raw exception message: media3 and `java.net` fold the credentialed
     * RTSP URI into theirs, and a leaked userinfo is exactly what this pipeline must not print.
     */
    data class Failed(val reason: String) : RepairResult()
}

/**
 * Repairs a stream whose SDP lacks the H.264 parameters media3 insists on. Implementations own
 * the proxy they start; [close] stops it. Callbacks may arrive on any thread.
 */
interface StreamRepairer {
    /**
     * A proxy URL right away when this stream's parameter sets are already known, else null.
     *
     * This STARTS a proxy from the cached parameter sets — closing whatever proxy the repairer was
     * running — so the URL returned is always live and can be opened immediately: a re-opened
     * camera never pays the failed direct DESCRIBE again. Only the cached SPS/PPS are reused; no
     * sniff is performed.
     */
    fun cachedProxyUrl(url: String): String?

    /** Sniff SPS/PPS from [url] (credential-free; [user]/[pass] separately) and start a proxy. */
    fun repair(url: String, user: String?, pass: String?, forceTcp: Boolean, onResult: (RepairResult) -> Unit)

    /** Forget cached parameter sets for [url] (the camera's resolution/profile changed). */
    fun invalidate(url: String)

    /** Stop every proxy this repairer started. Idempotent. */
    fun close()

    /** The no-op repairer: every repair fails immediately. The default for owners that are not
     *  given one; the app uses [LiveStreamRepairer.shared]. */
    object None : StreamRepairer {
        override fun cachedProxyUrl(url: String): String? = null
        override fun repair(url: String, user: String?, pass: String?, forceTcp: Boolean, onResult: (RepairResult) -> Unit) =
            onResult(RepairResult.Failed("no repairer installed"))
        override fun invalidate(url: String) = Unit
        override fun close() = Unit
    }
}
