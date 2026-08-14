package dev.rusty.app

/**
 * Maps [ControlServerStatus.State] to the string the General settings row's status line shows,
 * pulled out of [SettingsSheet] for the same reason [ControlNsdPlan] was: this is an ordinary
 * decision with no `android.*` dependency, worth pinning with a test rather than inspection.
 *
 * [ControlServerStatus.State.Running] with an empty url is the boot-before-Wi-Fi case: the
 * server bound successfully but has no site-local IPv4 address to advertise yet. It is a real,
 * expected state — not an error — so it must read as a distinct "waiting" message, never a blank
 * line and never a broken `http://:8765`.
 */
object ControlStatusLine {

    fun text(state: ControlServerStatus.State): String = when (state) {
        is ControlServerStatus.State.Stopped -> "Off"
        is ControlServerStatus.State.Starting -> "Starting…"
        is ControlServerStatus.State.Running ->
            if (state.url.isEmpty()) "Waiting for network…" else state.url
        is ControlServerStatus.State.Failed -> state.message
    }
}
