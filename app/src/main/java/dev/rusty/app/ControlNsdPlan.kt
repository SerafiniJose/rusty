package dev.rusty.app

/**
 * The pure decisions behind NSD advertisement of the remote-control API, pulled out of
 * [ControlNsdAdvertiser]/[ControlService] for the same reason [ControlRuntimeMath] was:
 * `android.net.nsd.NsdManager` cannot run on the JVM, so it is verified by build + on-device
 * acceptance only, but "is this URL worth advertising", "register / re-register / leave alone"
 * and "what goes in the TXT record" are ordinary decisions with no `android.*` dependency, and
 * Task 9's review explicitly called out burying testable logic like this inside an Android class
 * as a defect.
 */
object ControlNsdPlan {

    /** Service type for the wire contract the `rusty-homeassistant` HACS integration's zeroconf
     *  discovery matches on. Do not change without also changing that integration's
     *  `zeroconf: ["_rusty._tcp.local."]` manifest entry — see the design doc. */
    const val SERVICE_TYPE = "_rusty._tcp"

    /** What [ControlService] should do about the live registration when the advertised URL moves
     *  from [previousUrl] to [newUrl]. Both are the empty-string convention for "nothing to
     *  advertise" — never null, so a caller cannot forget the "first ever" case is just another
     *  transition from `""`. */
    enum class Action { NoOp, Register, Unregister }

    /**
     * - `"" -> ""` → [Action.NoOp]: never advertised, still nothing to advertise — covers both the
     *   very first bind before Wi-Fi is up, and a network tick that changes nothing.
     * - `"" -> url` → [Action.Register]: an address just arrived (first bind, or Wi-Fi coming up
     *   after a boot start).
     * - `url -> ""` → [Action.Unregister]: the address was lost.
     * - `urlA -> urlB` (both non-empty, different) → [Action.Register]: treated as a fresh
     *   register — [ControlNsdAdvertiser.register] drops any still-active registration first, so
     *   the caller does not need a separate "re-register" action to react to.
     * - `url -> url` (unchanged) → [Action.NoOp]: avoids re-registering on every network callback
     *   tick that does not actually move the address.
     */
    fun action(previousUrl: String, newUrl: String): Action = when {
        previousUrl == newUrl -> Action.NoOp
        newUrl.isEmpty() -> Action.Unregister
        else -> Action.Register
    }

    /**
     * The TXT attributes the design doc's wire contract fixes: a persistent per-install [deviceId]
     * (the HA integration's config-entry unique id — service name and address are NOT identities),
     * the fixed protocol version `api=1`, and [deviceName] again as `name=` for a client that wants
     * the display name without resolving+reading `/api/state` first. Order is insignificant (TXT is
     * a set of key/value pairs), so a [Map] is enough; converting to `NsdServiceInfo.setAttribute`
     * calls is Android glue, done in [ControlNsdAdvertiser].
     */
    fun txtAttributes(deviceId: String, deviceName: String): Map<String, String> = mapOf(
        "id" to deviceId,
        "api" to "1",
        "name" to deviceName,
    )
}
