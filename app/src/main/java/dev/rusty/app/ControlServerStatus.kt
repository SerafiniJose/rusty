package dev.rusty.app

/**
 * In-memory, process-static publisher of what the remote-control server is ACTUALLY doing. The
 * listener machinery — replaying registration, main-thread delivery, and why none of this is
 * persisted — lives in [StatusPublisher]; this adds the states and the stop rule.
 *
 * The design doc keeps this deliberately separate from the preference: `desiredEnabled` (the
 * toggle, persisted by [ControlSettings]) says what the user wants; this says what happened. A
 * bind failure must NOT flip the toggle off — it leaves the toggle ON, surfaces the reason in the
 * settings row, and is retried by the next service start (boot, toggle cycle, app launch). Mixing
 * the two into one persisted "enabled" flag would either hide the failure or silently disable a
 * feature the user asked for.
 *
 * The SERVICE is the sole source of [State.Running]: only it knows the bind succeeded. Callers with
 * no live service to speak for them may publish [State.Failed] (a start that never got off the
 * ground) or use [publishStoppedIfInactive] (a disable that has no running service to report its
 * own teardown).
 */
object ControlServerStatus : StatusPublisher<ControlServerStatus.State>(State.Stopped) {

    /**
     * [State.Running.url] is the page/address to show and advertise — non-empty only once the bind
     * succeeded. It is empty when the server is bound but the device has no routable LAN address
     * right now; like the renderer's null-URL-under-RUNNING, "running" and "reachable at X" are
     * different facts and the second one must never be faked (0.0.0.0 is not an address a phone on
     * the LAN can open).
     */
    sealed class State {
        object Stopped : State()
        object Starting : State()
        data class Running(val url: String) : State()
        data class Failed(val message: String) : State()
    }

    /**
     * Stop path. `stopService()` is a request — a live service publishes [State.Stopped] itself
     * from `onDestroy`. The two states with no live service to do that are [State.Failed] (which
     * is sticky, so that the settings row keeps showing why, and would otherwise survive the user
     * turning the feature off) and [State.Starting] (a rapid on→off can cancel the pending creation
     * before `onStartCommand` ever runs).
     */
    fun publishStoppedIfInactive() {
        val s = current()
        if (s is State.Failed || s is State.Starting) publish(State.Stopped)
    }
}
