package dev.rusty.app

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImmichConnectionSwapTest {

    @Test fun invalidateCompletesBeforeReload() = runTest {
        val order = mutableListOf<String>()
        ImmichConnectionSwap.launch(
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            invalidate = { order.add("invalidate") },
            reload = { order.add("reload") },
        )
        advanceUntilIdle()
        assertEquals(listOf("invalidate", "reload"), order)
    }

    @Test fun cancellationWhileTheClearIsBeingDispatchedStillRunsIt() = runTest {
        val order = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        // A hand-driven io dispatcher (NOT a second TestDispatcher — kotlinx-coroutines-test
        // rejects two schedulers in one test) so the swap can be parked in the one window
        // NonCancellable actually protects: the withContext block is queued for IO but has not
        // started. Cancelling INSIDE the block would prove nothing — invalidate() is a plain
        // non-suspending function, so once entered it runs to completion either way.
        val io = ManualDispatcher()
        ImmichConnectionSwap.launch(
            scope = scope,
            io = io,
            invalidate = { order.add("invalidate") },
            reload = { order.add("reload") },
        )
        advanceUntilIdle()      // outer launch runs, queues the block on io, suspends
        assertEquals(emptyList<String>(), order)
        scope.cancel()          // activity teardown lands while the block is still queued
        io.runQueued()          // without NonCancellable the block is dropped right here
        advanceUntilIdle()
        // The clear must still complete (a half-deleted cache keeps serving some of the old
        // server's photos); the reload is skipped, its scope having died — which is precisely
        // why HomeActivity's lifecycleScope, not the panel scope, owns this.
        assertEquals(listOf("invalidate"), order)
    }

    @Test fun cancellationBeforeTheBodyStartsRunsNothing() = runTest {
        val order = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        ImmichConnectionSwap.launch(
            scope = scope,
            io = StandardTestDispatcher(testScheduler),
            invalidate = { order.add("invalidate") },
            reload = { order.add("reload") },
        )
        scope.cancel() // cancel before the dispatcher ever runs the body
        advanceUntilIdle()
        // NonCancellable protects a block already entered; it cannot resurrect a coroutine
        // whose scope died before it was dispatched — that transaction simply never starts.
        // The invalidate is therefore NOT guaranteed by this class alone, which is exactly
        // why the caller must hand it a scope that outlives the settings sheet.
        assertEquals(emptyList<String>(), order)
    }

    /** Dispatcher that holds everything sent to it until the test says otherwise. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        fun runQueued() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }
}
