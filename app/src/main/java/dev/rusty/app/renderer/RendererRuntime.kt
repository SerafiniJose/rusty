package dev.rusty.app.renderer

data class RendererIdentity(
    val deviceName: String,
    val lanAddress: String?,
    val status: RendererStatus,
)

/** One coherent UI snapshot: renderer state (null when no service) + identity (name/address/status). */
data class RendererUiSnapshot(
    val state: RendererState?,
    val identity: RendererIdentity,
)

/** Semantic transport commands the screen issues; the backend translates them into RendererEvents. */
sealed class RendererCommand {
    object Play : RendererCommand()
    object Pause : RendererCommand()
    object Stop : RendererCommand()
    data class Seek(val positionMs: Long) : RendererCommand()
}

/** The UI-facing renderer surface. The fragment depends only on this.
 *  Named distinctly from [dev.rusty.app.renderer]'s other `RendererRuntime` (the HTTP/SOAP seam
 *  in RendererHttpProtocol.kt) — same package, unrelated concerns, different shape. */
interface RendererUiRuntime {
    fun current(): RendererUiSnapshot
    fun positionMs(): Long?
    fun addListener(l: (RendererUiSnapshot) -> Unit)
    fun removeListener(l: (RendererUiSnapshot) -> Unit)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
}

/**
 * Process-static bridge between the (unbound) MediaRendererService and any UI. Mirrors
 * [RendererStatusPublisher]: listeners are notified on the main thread via an injectable dispatcher
 * and registration replays the current snapshot. Status + LAN address are reused from
 * [RendererStatusPublisher]; renderer state + position + device name come from the attached backend.
 */
object RendererRuntimeHolder : RendererUiRuntime {

    /** Implemented by [MediaRendererService]; supplies live state/position/name and a command sink. */
    interface Backend {
        fun state(): RendererState?
        fun positionMs(): Long?
        fun deviceName(): String
        fun dispatch(command: RendererCommand)
    }

    private val listeners = mutableSetOf<(RendererUiSnapshot) -> Unit>()
    private var backend: Backend? = null

    private var dispatch: (Runnable) -> Unit = { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }

    /** Registered once, forwards RendererStatusPublisher changes into our listeners. */
    private val statusListener: (RendererStatusSnapshot) -> Unit = { publishChanged() }
    private var statusWired = false

    fun setDispatcher(d: (Runnable) -> Unit) = synchronized(this) { dispatch = d }

    fun attach(b: Backend) {
        val wireStatus: Boolean
        synchronized(this) {
            backend = b
            wireStatus = !statusWired
            if (wireStatus) statusWired = true
        }
        if (wireStatus) RendererStatusPublisher.addListener(statusListener)
        publishChanged()
    }

    fun detach(b: Backend) {
        synchronized(this) { if (backend === b) backend = null }
        publishChanged()
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTest() = synchronized(this) {
        listeners.clear()
        backend = null
    }

    override fun current(): RendererUiSnapshot {
        val (b, statusSnap) = synchronized(this) { backend to RendererStatusPublisher.current() }
        return RendererUiSnapshot(
            state = b?.state(),
            identity = RendererIdentity(
                deviceName = b?.deviceName() ?: "",
                lanAddress = statusSnap.descriptionUrl,
                status = statusSnap.status,
            ),
        )
    }

    override fun positionMs(): Long? = synchronized(this) { backend }?.positionMs()

    override fun addListener(l: (RendererUiSnapshot) -> Unit) {
        val (d, snap) = synchronized(this) { listeners.add(l); dispatch to current() }
        d(Runnable { if (synchronized(this) { l in listeners }) l(snap) })
    }

    override fun removeListener(l: (RendererUiSnapshot) -> Unit) =
        synchronized(this) { listeners.remove(l); Unit }

    /** Recompute + notify all listeners on the main thread. Called on state, status, or rename change. */
    fun publishChanged() {
        val (d, targets, snap) = synchronized(this) {
            Triple(dispatch, listeners.toList(), current())
        }
        targets.forEach { l -> d(Runnable { if (synchronized(this) { l in listeners }) l(snap) }) }
    }

    override fun play() = synchronized(this) { backend }?.dispatch(RendererCommand.Play) ?: Unit
    override fun pause() = synchronized(this) { backend }?.dispatch(RendererCommand.Pause) ?: Unit
    override fun stop() = synchronized(this) { backend }?.dispatch(RendererCommand.Stop) ?: Unit
    override fun seek(positionMs: Long) = synchronized(this) { backend }?.dispatch(RendererCommand.Seek(positionMs)) ?: Unit
}
