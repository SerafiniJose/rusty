package dev.rusty.app

/**
 * Pure navigation state: the current feature id and the enabled ring.
 *
 * Android-free — no android.* imports, no FragmentManager. Safe to unit-test on the JVM.
 *
 * @param persisted The id that was last persisted (e.g. from SharedPreferences); null on first
 *   launch. Restored only if it is still in [enabled]; otherwise falls back to the first enabled
 *   feature or [FeatureId.SPOTIFY] if the list is empty.
 * @param enabled   The ordered list of currently-enabled features (in ring order).
 */
class FeatureNavState(persisted: FeatureId?, enabled: List<FeatureId>) {

    private var _ring: List<FeatureId> = enabled.toList()

    /**
     * The currently-active feature. May be set directly by [FeatureNavigator] after a successful
     * fragment transaction, or updated by [onEnabledChanged] when the current feature is disabled.
     */
    var current: FeatureId = resolveInitial(persisted, enabled)
        set(value) {
            field = value
        }

    /**
     * Returns the next feature in the enabled ring after [current], wrapping around. Returns
     * [current] unchanged when the ring has fewer than two members or [current] is not in it.
     */
    fun next(): FeatureId = FeatureRing.next(_ring, current)

    /**
     * Called when the set of enabled features changes (e.g. HA is toggled). If [current] is no
     * longer in [newEnabled], falls back to the next enabled feature (or the first enabled, or
     * [current] unchanged if the new list is empty).
     */
    fun onEnabledChanged(newEnabled: List<FeatureId>) {
        _ring = newEnabled.toList()
        if (current !in newEnabled) {
            current = newEnabled.firstOrNull() ?: current
        }
    }

    companion object {
        private fun resolveInitial(persisted: FeatureId?, enabled: List<FeatureId>): FeatureId =
            when {
                persisted != null && persisted in enabled -> persisted
                enabled.isNotEmpty() -> enabled.first()
                else -> FeatureId.SPOTIFY
            }
    }
}
