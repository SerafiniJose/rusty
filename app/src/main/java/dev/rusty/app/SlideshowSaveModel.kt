package dev.rusty.app

/**
 * The outcome of an Immich "Save" (sign-in) attempt, decided purely from the identity call and the
 * capability probe so it is JVM-unit-testable.
 *
 * Identity is best-effort: Immich returns 403 on `GET /api/users/me` for a limited/scoped API key
 * (the kind a photo frame is meant to use — least privilege), even though that key authenticates
 * the slideshow perfectly. So a failed identity read must NOT be reported as a bad key; the
 * capability probe is the real "does this key work" gate. See [SlideshowSaveModel.of].
 */
sealed interface SlideshowSaveResult {
    /** Identity read succeeded — show "{name} · {host}". */
    data class SignedIn(val name: String, val unavailable: List<String>) : SlideshowSaveResult

    /** Key works for the slideshow (at least one capability), but the account name couldn't be
     *  read (scoped key lacks user access). Header falls back to "{host} · key saved". */
    data class SavedNoIdentity(val unavailable: List<String>) : SlideshowSaveResult

    /** The key authenticates nothing — genuinely wrong/invalid key. */
    object InvalidKey : SlideshowSaveResult

    /** The host didn't answer (down / wrong address / not an Immich API). */
    object Unreachable : SlideshowSaveResult
}

object SlideshowSaveModel {

    /**
     * Decide the Save outcome. [probes] is the capability-probe result; the caller runs it whenever
     * the host answered (identity Ok, or an AUTH 401/403) and passes an empty list when it skipped
     * the probe because the host was unreachable (so the four sequential 8s-timeout probes never
     * stall a dead-host Save).
     */
    fun of(user: ImmichResult<ImmichUser>, probes: List<ImmichProbe>): SlideshowSaveResult {
        val unavailable = probes.filter { !it.ok }.map { it.label }
        return when (user) {
            is ImmichResult.Ok -> {
                val display = user.value.name.ifBlank { user.value.email }
                // A valid id but blank name AND email carries no usable identity — treat like a
                // scoped key that couldn't read one, rather than showing "Signed in as .".
                if (display.isBlank()) SlideshowSaveResult.SavedNoIdentity(unavailable)
                else SlideshowSaveResult.SignedIn(display, unavailable)
            }
            is ImmichResult.Error -> when (user.kind) {
                ImmichErrorKind.UNREACHABLE -> SlideshowSaveResult.Unreachable
                // 401 (bad key) and 403 (valid but scoped) both arrive here; the probe tells them
                // apart — a scoped key still reads at least one capability, an invalid one reads none.
                ImmichErrorKind.AUTH ->
                    if (probes.any { it.ok }) SlideshowSaveResult.SavedNoIdentity(unavailable)
                    else SlideshowSaveResult.InvalidKey
            }
        }
    }
}
