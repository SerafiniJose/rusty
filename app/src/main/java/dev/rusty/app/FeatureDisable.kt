package dev.rusty.app

/** Pure decision for the shell when a feature is turned off: which feature to switch to, if any. */
object FeatureDisable {
    /**
     * The feature to show after [disabledId] is turned off, or null if no switch is needed (it
     * wasn't the active feature). When it WAS active, fall back to the first still-enabled feature,
     * or [FeatureId.SPOTIFY] if none remain. Must be computed with [activeId] captured BEFORE the
     * nav ring is mutated.
     */
    fun switchTargetOnDisable(
        disabledId: FeatureId,
        activeId: FeatureId,
        stillEnabled: List<FeatureId>,
    ): FeatureId? =
        if (activeId != disabledId) null
        else stillEnabled.firstOrNull() ?: FeatureId.SPOTIFY
}
