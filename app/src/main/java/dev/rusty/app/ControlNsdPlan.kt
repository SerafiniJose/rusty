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

    /** What [ControlService] should do about the live registration when the advertisement moves
     *  from [previousKey] to [newKey] — [advertKey] values, i.e. the advertised URL folded together
     *  with the TXT record it carries, NOT bare URLs. Both use the empty-string convention for
     *  "nothing to advertise" — never null, so a caller cannot forget the "first ever" case is just
     *  another transition from `""`. */
    enum class Action { NoOp, Register, Unregister }

    /**
     * Compares two [advertKey] values, so "the advertisement changed" covers a moved address AND a
     * TXT-only change (camera sharing, or its password, being toggled) — not only the URL.
     *
     * - `"" -> ""` → [Action.NoOp]: never advertised, still nothing to advertise — covers both the
     *   very first bind before Wi-Fi is up, and a network tick that changes nothing.
     * - `"" -> key` → [Action.Register]: something to advertise just arrived (first bind, or Wi-Fi
     *   coming up after a boot start).
     * - `key -> ""` → [Action.Unregister]: the address was lost — [advertKey] folds an empty URL to
     *   the empty key whatever the TXT record says, so this is the only way to reach it.
     * - `keyA -> keyB` (both non-empty, different) → [Action.Register]: treated as a fresh
     *   register — [ControlNsdAdvertiser.register] drops any still-active registration first, so
     *   the caller does not need a separate "re-register" action to react to.
     * - `key -> key` (unchanged) → [Action.NoOp]: avoids re-registering on every network callback
     *   tick, camera-share status change or pref write that does not actually move the
     *   advertisement.
     */
    fun action(previousKey: String, newKey: String): Action = when {
        previousKey == newKey -> Action.NoOp
        newKey.isEmpty() -> Action.Unregister
        else -> Action.Register
    }

    /**
     * Whether [share] is a state worth advertising a camera for — the `cam`/`rtsp` half of
     * [txtAttributes], resolved here rather than at the [ControlService] call site because it is
     * pure logic over a sealed class and this object exists precisely so decisions like it are
     * pinned by tests instead of by inspection.
     *
     * Written as an EXHAUSTIVE `when` on purpose. As the pair of `!is` checks it replaces, a new
     * [CameraShareStatus.State] subtype would silently fall through to "advertise" with no compiler
     * error — failing open. Telling the LAN "this device has a camera" is a promise another Rusty
     * device (and the Home Assistant integration) acts on, so the safe default for an unconsidered
     * state is a build failure, not an advertisement.
     *
     * [CameraShareStatus.State.Unavailable] does NOT advertise. Every path that publishes it leaves
     * the share unable to answer: `CameraShareService.fail` stops the service outright, so nothing
     * is listening on [CameraShareSettings.RTSP_PORT] at all; the permission and
     * foreground-start-refused guards publish it when the service never started; and inside
     * [CameraShareHub] itself, `ensureStarted()`'s `Failed` branch — the most common of these in
     * practice, since it is what answers when the camera fails to open on the very first DESCRIBE —
     * plus `onPipelineError`, `configFailed()` and `restartForLensChange()`'s reopen failure, all
     * leave the RTSP socket itself open and answer every DESCRIBE with 503. Advertising through it
     * hands a discovering device a camera that only fails when played — and it is not a blip that
     * clears itself, since the service-level cases only heal on the next app-foreground re-sync.
     */
    fun advertisesCamera(share: CameraShareStatus.State): Boolean = when (share) {
        is CameraShareStatus.State.Ready -> true
        is CameraShareStatus.State.Streaming -> true
        is CameraShareStatus.State.Off -> false
        is CameraShareStatus.State.Unavailable -> false
        is CameraShareStatus.State.Unsupported -> false
    }

    /**
     * The TXT attributes the design doc's wire contract fixes: a persistent per-install [deviceId]
     * (the HA integration's config-entry unique id — service name and address are NOT identities),
     * the fixed protocol version `api=1`, and [deviceName] again as `name=` for a client that wants
     * the display name without resolving+reading `/api/state` first. Order is insignificant (TXT is
     * a set of key/value pairs), so a [Map] is enough; converting to `NsdServiceInfo.setAttribute`
     * calls is Android glue, done in [ControlNsdAdvertiser].
     *
     * [cameraShared] and [authRequired] are this device's camera-share facts (spec: camera-share
     * design doc), read once by the caller and handed in rather than looked up here — this
     * function must stay a pure map from booleans to keys, not a second place that reaches into
     * `CameraShareStatus`/`ControlSettings` itself; [advertisesCamera] is where the
     * [CameraShareStatus.State] half of that question is answered.
     *
     * `cam`/`rtsp` only appear when [cameraShared] is true; `auth` only appears alongside them —
     * INSIDE that same `if`, so it can never describe an API that has no stream — and only when
     * [authRequired] is *also* true. An unshared camera never advertises a port nobody is listening
     * on, and an open share never claims a password it does not enforce (a caller must resolve "is a
     * password actually enforced", not merely "is the switch on", before calling this — see
     * [ControlSettings.requiredPassword]).
     */
    fun txtAttributes(
        deviceId: String,
        deviceName: String,
        cameraShared: Boolean = false,
        authRequired: Boolean = false,
    ): Map<String, String> = buildMap {
        put("id", deviceId)
        put("api", "1")
        put("name", deviceName)
        if (cameraShared) {
            put("cam", "1")
            put("rtsp", CameraShareSettings.RTSP_PORT.toString())
            if (authRequired) put("auth", "1")
        }
    }

    /**
     * What [action] diffs: the advertised URL plus the TXT record it would carry, folded into one
     * string, so a change to EITHER — a moved address or a TXT-only change like camera sharing or
     * its password being toggled — is treated as "the advertisement changed" rather than only the
     * URL. `""` when there is nothing to advertise ([url] empty), the same convention [action]
     * already used for "no URL" before TXT was part of the comparison; the TXT map is irrelevant in
     * that case; a caller should not have to special-case building it just to reach this branch.
     * Otherwise `url + "|" + `sorted `"k=v"` pairs joined by `","` — sorted so the same attributes
     * in a different [Map] iteration order fold to the same key, never a false "changed".
     */
    fun advertKey(url: String, txt: Map<String, String>): String =
        if (url.isEmpty()) "" else url + "|" + txt.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
}
