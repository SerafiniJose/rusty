package dev.rusty.app

import android.view.KeyEvent

/** What the shell must do with a key that arrives while the screensaver overlay is showing. */
enum class SaverKeyAction {
    /** Route to the Spotify receiver via [TvRemote.dispatchTransportKey] → NativeBridge. */
    SPOTIFY_TRANSPORT,
    /** Offer to the remote-owning theme's [ScreensaverTheme.onNavKey]. */
    SLIDESHOW_NAV,
    /** Existing wake path ([ScreensaverController.onWakeKey]), consumed. */
    WAKE,
    /** Swallow: consumed with no effect (dead key on an owning slideshow). */
    CONSUME,
}

/**
 * Pure decisions for the shell's hardware-key dispatch (see the 2026-07-22 media-keys spec).
 * The contract: media keys mean MUSIC everywhere; the D-pad means PHOTOS whenever a slideshow
 * owns the remote; BACK/UP are the only exits from an owning slideshow; system keys are never
 * consumed. Kept Android-free (primitives only) so a plain JVM test exercises the whole matrix —
 * KeyEvent's `static final int` constants inline fine under JVM tests, its methods don't.
 */
object ShellKeyRouting {

    /**
     * System keys: the voice assistant (SEARCH, ASSIST, VOICE_ASSIST) and the volume rocker
     * (VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE). Never consumed by ANY path — with or without a saver
     * up they fall straight through to the system, so the assistant and the volume rocker work on
     * top of the app. Callers check this BEFORE [routeWhileSaverShowing]; the router never sees
     * these keys.
     */
    fun isSystemKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    /**
     * The only keys a remote-owning theme may ever see. Enforced here, not trusted to the theme
     * (the same shell-enforces-the-key-set stance the old per-theme media-key routing took).
     * ENTER is included because some remotes send it for the OK button; it means CENTER.
     */
    fun isNavKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    /** BACK or UP: the guaranteed escapes from a remote-owning slideshow. Every remote has BACK. */
    private fun isExitKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP

    /**
     * The whole while-saver-showing decision. [slideshowOwnsRemote] = the active theme reports
     * [ScreensaverTheme.ownsRemote] (a slideshow actually running photos); [spotifyActive] = the
     * receiver has a track loaded/playing/paused ([VisualState.ACTIVE]). Precondition: the caller
     * has already passed system keys through ([isSystemKey]).
     */
    fun routeWhileSaverShowing(
        keyCode: Int,
        slideshowOwnsRemote: Boolean,
        spotifyActive: Boolean,
    ): SaverKeyAction {
        if (TvRemote.isTransportKey(keyCode) && spotifyActive) return SaverKeyAction.SPOTIFY_TRANSPORT
        if (!slideshowOwnsRemote) return SaverKeyAction.WAKE
        if (isNavKey(keyCode)) return SaverKeyAction.SLIDESHOW_NAV
        if (isExitKey(keyCode)) return SaverKeyAction.WAKE
        return SaverKeyAction.CONSUME
    }

    /**
     * Which way PLAY_PAUSE toggles, from the live receiver status string. "Playing" is the same
     * literal [VisualState] and the dashboard render on; a paused/loading/idle receiver gets play().
     */
    fun togglesToPause(status: String): Boolean = status == "Playing"
}
