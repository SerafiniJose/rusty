package dev.rusty.app

/**
 * The destinations the remote control can put on the device's screen.
 *
 * Three of them are top-level [FeatureId]s; [LOCKSCREEN] is not, because the screensaver is shown
 * OVER whichever feature is current rather than replacing it. From the phone that distinction is
 * invisible and unhelpful — "what is Rusty showing right now" has exactly four answers — so the
 * API flattens the two mechanisms into one list and the shell half ([PanelControlRelay]) is what
 * knows that reaching LOCKSCREEN means `show()` and reaching the others means `switchTo()`.
 *
 * [wire] is the published JSON vocabulary of `GET /api/state` and `POST /api/panel`. It is a
 * declared constant rather than `name.lowercase()` on purpose: renaming an enum constant is a
 * refactor, and it must not silently change the API that shipped control pages speak.
 */
enum class ControlPanelId(val wire: String) {
    SPOTIFY("spotify"),
    HOME_ASSISTANT("home_assistant"),
    DLNA("dlna"),
    LOCKSCREEN("lockscreen");

    /** The feature this panel shows, or null for [LOCKSCREEN] — an overlay, not a feature. */
    val featureId: FeatureId?
        get() = when (this) {
            SPOTIFY -> FeatureId.SPOTIFY
            HOME_ASSISTANT -> FeatureId.HOME_ASSISTANT
            DLNA -> FeatureId.DLNA
            LOCKSCREEN -> null
        }

    companion object {
        /** Parses a wire value; null for anything unknown (the router answers 400). */
        fun fromWire(value: String?): ControlPanelId? = values().firstOrNull { it.wire == value }

        /** The panel that shows [id]. Total: every [FeatureId] has exactly one panel. */
        fun of(id: FeatureId): ControlPanelId = values().first { it.featureId == id }
    }
}

/**
 * Wire vocabulary for the lockscreen's theme, mapping 1:1 onto [ScreensaverThemeId].
 *
 * Unlike [ControlPanelId] this is a mapping object rather than a parallel enum: the themes are
 * exactly the screensaver's own, so duplicating the enum would only create two lists to keep in
 * step. The wire values are the lowercased constant names, and [ControlPanelsTest] pins every one
 * of them, so renaming a [ScreensaverThemeId] constant fails a test instead of quietly breaking
 * the API.
 */
object ControlLockscreenThemes {
    fun wire(id: ScreensaverThemeId): String = id.name.lowercase()

    /** Parses a wire value; null for anything unknown (the router answers 400). */
    fun fromWire(value: String?): ScreensaverThemeId? =
        ScreensaverThemeId.values().firstOrNull { wire(it) == value }

    /**
     * The themes the remote may select, in the settings-selector order.
     *
     * `SLIDESHOW` is dropped while the Slideshow feature is off, mirroring [SlideshowDisable]: the
     * in-app picker cannot select a theme that would refuse to mount, and neither may the remote.
     */
    fun selectable(slideshowEnabled: Boolean): List<ScreensaverThemeId> =
        ScreensaverThemeId.values().filter {
            slideshowEnabled || it != ScreensaverThemeId.SLIDESHOW
        }
}
