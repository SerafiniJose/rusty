package dev.rusty.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams

/**
 * The announcement card, wired for one screen.
 *
 * [HomeActivity] and [LyricsActivity] are the app's only two activities and either can be the
 * frontmost window during ordinary use, so each carries its own copy of the card — the same
 * arrangement, and for the same reason, as [CameraShareGlyph]. What differs is that this one has
 * state to keep (an animation, a timer, which announcement is on screen), so it is a class each
 * activity holds rather than a stateless listener factory.
 *
 * It shows what the control page asked the device to say, for as long as the device is saying it.
 * Every timing rule lives in [AnnouncementCardModel]; this class only renders them. A Home
 * Assistant announcement never appears here, because it arrives as audio and carries no text.
 *
 * [enabled] is the Voice tab's "Show on screen" switch, read on every render rather than at
 * attach, so turning it off takes a card already up away with the next recheck. The relay is
 * fed either way; only the drawing stops.
 */
class AnnouncementCard(
    private val root: View,
    private val label: TextView,
    private val enabled: () -> Boolean,
) {

    /** Enter: falls from above the window edge and settles with a small overshoot. */
    private companion object {
        const val ENTER_MS = 380L
        const val EXIT_MS = 260L
        const val OVERSHOOT_TENSION = 1.1f
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The announcement currently drawn, so a second one arriving mid-card swaps the words instead
     *  of replaying the entrance, and a re-render for any other reason is inert. */
    private var shownId: Long? = null

    /** Posted by the relay's callback, which arrives on an HTTP pool thread. */
    private val relayListener: () -> Unit = { handler.post(::render) }

    private val recheck = Runnable { render() }

    fun attach() {
        AnnouncementRelay.addListener(relayListener)
        render()
    }

    fun detach() {
        AnnouncementRelay.removeListener(relayListener)
        handler.removeCallbacks(recheck)
        root.animate().cancel()
    }

    /**
     * Re-applies the card's top margin so it clears the system bars, from the host's own
     * window-insets listener. A margin rather than padding, for the reason spelled out in
     * [CameraShareGlyph.applyInsetMargin]: padding would eat into the card's own surface.
     */
    fun applyInsetMargin(bars: Insets, density: Float) {
        val base = (16 * density).toInt()
        root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = bars.top + base
            leftMargin = bars.left + base
            rightMargin = bars.right + base
        }
    }

    private fun render() {
        handler.removeCallbacks(recheck)
        val announcement = AnnouncementRelay.current()
        val now = SystemClock.elapsedRealtime()
        val visible = AnnouncementCardModel.visible(
            announcement?.publishedAtMs, announcement?.settled ?: false, now,
        )
        if (!visible || announcement == null || !enabled()) {
            hide()
            return
        }
        show(announcement)
        handler.postDelayed(
            recheck,
            AnnouncementCardModel.nextCheckDelayMs(announcement.publishedAtMs, announcement.settled, now),
        )
    }

    private fun show(announcement: Announcement) {
        if (shownId == announcement.id && root.isVisible) return
        val entering = !root.isVisible
        shownId = announcement.id
        setText(announcement.text)
        if (!entering) return

        root.animate().cancel()
        root.alpha = 1f
        root.isVisible = true
        // The drop distance is the card's own height plus its margin, which is only known once it
        // has been measured — so park it there in the pre-draw and let it fall from the next frame.
        root.doOnPreDraw {
            val margin = (root.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            root.translationY = -(root.height + margin).toFloat()
            root.animate()
                .translationY(0f)
                .setDuration(ENTER_MS)
                // Overshoot is where "settle" comes from: it dips just past its resting place and
                // comes back. No alpha on the way in — the card starts above the window edge, so
                // there is nothing to fade.
                .setInterpolator(OvershootInterpolator(OVERSHOOT_TENSION))
                .start()
        }
        // Spoken aloud already, but a screen reader user may have the volume elsewhere.
        root.announceForAccessibility(announcement.text)
    }

    private fun hide() {
        shownId = null
        if (!root.isVisible) return
        val margin = (root.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        root.animate().cancel()
        root.animate()
            .translationY(-(root.height + margin).toFloat())
            .alpha(0f)
            .setDuration(EXIT_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction { root.isVisible = false }
            .start()
    }

    /**
     * Sets the words, then steps the size down if they did not fit in
     * [AnnouncementCardModel.MAX_FULL_SIZE_LINES] — measured rather than guessed from the character
     * count, because the line count is what actually decides whether the card stays a card.
     */
    private fun setText(text: String) {
        label.textSize = AnnouncementCardModel.TEXT_SIZE_SP
        label.text = text
        label.doOnPreDraw {
            val size = AnnouncementCardModel.textSizeSp(label.lineCount)
            if (label.textSize != size * label.resources.displayMetrics.scaledDensity) {
                label.textSize = size
            }
        }
    }
}
