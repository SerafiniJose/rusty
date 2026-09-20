package dev.rusty.app

/** Wording for the Control page row in the Remote Control tab, from the server's own status. */
object ControlPageQrModel {
    /** [address] is the row's sub-line; [url] the address to encode, null while there is none. */
    data class Row(val address: String, val url: String?, val qrEnabled: Boolean)

    fun row(state: ControlServerStatus.State): Row = when (state) {
        is ControlServerStatus.State.Running ->
            // Bound with no routable LAN address publishes an empty URL — see that state's doc.
            if (state.url.isEmpty()) Row("Waiting for network", null, false)
            else Row(state.url, state.url, true)
        ControlServerStatus.State.Starting -> Row("Starting the server…", null, false)
        ControlServerStatus.State.Stopped -> Row("Not running", null, false)
        is ControlServerStatus.State.Failed -> Row("Not running — ${state.message}", null, false)
    }
}
