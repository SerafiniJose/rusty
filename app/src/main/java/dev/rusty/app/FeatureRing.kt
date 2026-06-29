package dev.rusty.app

/**
 * Pure switch-ring math, independent of Android, so it is unit-testable. Given the full ordered
 * feature list and a predicate for which are enabled, computes the cycle order and the next
 * feature the bottom-right switch-button should advance to.
 */
object FeatureRing {
    /** The enabled features, in declaration order. */
    fun enabled(all: List<FeatureId>, isEnabled: (FeatureId) -> Boolean): List<FeatureId> =
        all.filter(isEnabled)

    /**
     * The next feature after [current] in the enabled [ring], wrapping around. Returns [current]
     * unchanged when the ring has fewer than two members or [current] is not in it.
     */
    fun next(ring: List<FeatureId>, current: FeatureId): FeatureId {
        if (ring.size < 2) return current
        val i = ring.indexOf(current)
        if (i < 0) return current
        return ring[(i + 1) % ring.size]
    }
}
