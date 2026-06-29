package dev.rusty.app

/**
 * The selectable screensaver themes. Declaration order is the settings-selector order.
 * Persisted by [prefValue]; an unknown/missing pref falls back to [CLOCK] — which also covers a
 * previously-persisted "PARTY" now that the Party theme is shelved (its impl stays in the tree).
 */
enum class ScreensaverThemeId {
    CLOCK, OLED, CANVAS;

    val prefValue: String get() = name

    companion object {
        fun fromPrefValue(value: String?): ScreensaverThemeId =
            values().firstOrNull { it.name == value } ?: CLOCK
    }
}
