package dev.rusty.app

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams

/**
 * Shared wiring for the "someone is watching this camera" glyph: the small `ic_mdi_cctv` dot every
 * top-level screen shows in its top-end corner while — and ONLY while — a camera-share viewer is
 * actually attached. [HomeActivity] and [LyricsActivity] are the app's only two activities, and
 * each one's window can be the frontmost thing on screen during ordinary use: tapping the album art
 * opens Lyrics as a separate, opaque, full-screen `AppCompatActivity` that can stay up for a whole
 * song, and while it is up Home's own copy of the glyph is not drawn at all. So both screens need
 * their own `ivCameraShareGlyph` view, wired identically; only the logic below is shared.
 *
 * This is a privacy signal, not decoration: it tracks [CameraShareStatus.State.Streaming] alone —
 * never merely "sharing enabled" (that would train people to ignore it) — and it must never linger
 * once the last viewer leaves or the share stops. [CameraShareStatus.addListener] replays the
 * current state on every registration, so a viewer already attached when a screen opens is caught
 * too.
 */
object CameraShareGlyph {

    /** Matches the glyph's own XML margins in both layouts — the base gap from the safe-area edge
     *  before the system-bar inset is added on top by [applyInsetMargin]. */
    private const val MARGIN_DP = 14

    /**
     * The visibility listener for the glyph [glyph] resolves. [glyph] is a supplier rather than the
     * [View] itself so this can be assigned to an eagerly-initialized field before `setContentView`
     * has run — the lookup only happens when the returned function is actually invoked, exactly as
     * the inline lambda this replaced did. Callers register the RETURNED instance with
     * [CameraShareStatus.addListener] and remove that SAME instance (never a fresh lambda) in their
     * teardown — held as a field, exactly like every other listener in these activities.
     */
    fun listener(glyph: () -> View): (CameraShareStatus.State) -> Unit = { state ->
        glyph().isVisible = state is CameraShareStatus.State.Streaming
    }

    /**
     * Re-applies [glyph]'s margin so it clears the system bars by the same amount as the rest of
     * that screen's chrome — call this from the screen's own window-insets listener. A margin, not
     * padding: the glyph is a fixed 22dp box, and padding would eat into its own drawable instead of
     * pushing the whole box off the system bar.
     */
    fun applyInsetMargin(glyph: View, bars: Insets, density: Float) {
        val margin = (MARGIN_DP * density).toInt()
        glyph.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = bars.top + margin
            marginEnd = bars.right + margin
        }
    }
}
