package dev.rusty.app.renderer

import java.util.ArrayDeque

/**
 * Serialized store over [reduceRenderer] — the single funnel the spec's Architecture
 * intro requires: SOAP threads, ExoPlayer callbacks, the Spotify bridge, timers and
 * teardown all dispatch here; the reducer's generation tokens make stale inputs inert.
 * Same pending-FIFO/single-drain idea as [dev.rusty.app.ReceiverStateStore], with
 * effects interleaved into the same queue so handler + listeners share one total order.
 *
 * Listeners are wrapped with a per-listener revision guard (see [Wrapper]) so a listener
 * registered while a drain is in flight never observes pre-registration snapshots still
 * pending in the queue, and never receives the same revision twice.
 */
class RendererStore(private val effectHandler: EffectHandler) {
    fun interface EffectHandler { fun handle(effect: RendererEffect) }
    fun interface Listener { fun onState(state: RendererState, revision: Long) }

    private sealed class Item {
        data class Snapshot(val state: RendererState, val revision: Long) : Item()
        data class Effect(val effect: RendererEffect) : Item()
    }

    private val lock = Any()
    private var current = RendererState()
    private var revision = 0L
    private val pending = ArrayDeque<Item>()
    private var draining = false
    private val listeners = ArrayList<Wrapper>()

    val state: RendererState get() = synchronized(lock) { current }

    fun dispatch(event: RendererEvent) {
        val drain = synchronized(lock) {
            val (next, effects) = reduceRenderer(current, event)
            current = next
            revision += 1
            for (e in effects) pending.addLast(Item.Effect(e))
            pending.addLast(Item.Snapshot(next, revision))
            startDrainLocked()
        }
        if (drain) drainLoop()
    }

    /**
     * Registers [l] and enqueues a plain broadcast snapshot of the current state through the same
     * FIFO. The new wrapper's revision guard is seeded one below the current revision, so any
     * pre-registration snapshot still lingering in [pending] is dropped for it, while existing
     * wrappers (which already saw the current revision) drop the re-broadcast — the new listener
     * receives exactly one initial delivery, in total order.
     */
    fun addListener(l: Listener) {
        val drain = synchronized(lock) {
            listeners.add(Wrapper(l, seedRevision = revision - 1))
            pending.addLast(Item.Snapshot(current, revision))
            startDrainLocked()
        }
        if (drain) drainLoop()
    }

    fun removeListener(l: Listener) {
        synchronized(lock) { listeners.removeAll { it.delegate === l } }
    }

    private fun startDrainLocked(): Boolean {
        if (draining) return false
        draining = true
        return true
    }

    private fun drainLoop() {
        while (true) {
            val item: Item
            val targets: List<Wrapper>
            synchronized(lock) {
                if (pending.isEmpty()) { draining = false; return }
                item = pending.removeFirst()
                targets = ArrayList(listeners)
            }
            when (item) {
                is Item.Effect ->
                    try {
                        effectHandler.handle(item.effect)
                    } catch (_: Throwable) {
                        // Swallow so one faulty effect never wedges the drain (pure JVM: no Log).
                    }
                is Item.Snapshot ->
                    for (t in targets) {
                        try {
                            t.deliver(item.state, item.revision)
                        } catch (_: Throwable) {
                            // Swallow so one faulty listener never wedges the drain or starves peers.
                        }
                    }
            }
        }
    }

    /** Per-listener wrapper that drops any snapshot whose revision <= its last-delivered. */
    private class Wrapper(val delegate: Listener, seedRevision: Long) {
        private var lastRevision: Long = seedRevision
        fun deliver(state: RendererState, revision: Long) {
            if (revision <= lastRevision) return
            lastRevision = revision
            delegate.onState(state, revision)
        }
    }
}
