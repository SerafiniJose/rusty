package dev.rusty.app

/**
 * Pure model for the expandable launcher. Computes the top-to-bottom display order of menu items
 * (a Lock action plus one entry per enabled feature) so it is unit-testable without Android.
 */
object LauncherMenu {
    enum class Kind { LOCK, FEATURE }

    data class LauncherItem(val kind: Kind, val featureId: FeatureId?)

    /**
     * Top-to-bottom display order: LOCK on top, then features in reverse ring order so the
     * first-declared enabled feature is the bottom pill, nearest the launcher toggle.
     */
    fun items(enabled: List<FeatureId>): List<LauncherItem> =
        listOf(LauncherItem(Kind.LOCK, null)) +
            enabled.reversed().map { LauncherItem(Kind.FEATURE, it) }

    fun isActive(item: LauncherItem, current: FeatureId): Boolean =
        item.kind == Kind.FEATURE && item.featureId == current
}
