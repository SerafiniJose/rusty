package dev.rusty.app

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Drives the idle⇄active "bloom". Holds references to the shared views; the Activity
 * calls [apply] on a visual-state edge. Cancels any in-flight animation before starting
 * the next so rapid flips never stack.
 */
class BloomController(
    private val clock: TextView,
    private val idleViews: List<View>,    // date, status
    private val activeViews: List<View>,  // identitySuffix, albumArtCard, playingInfo (children animate transitively)
    private val mesh: AmbientMeshView,
    private val wash: View,
    private val scrim: View
) {
    private var current: VisualState? = null
    private val cornerScale = BloomGeometry.CORNER_SCALE
    // The clock lives in the shell's full-window, inset-padded chrome layer; derive the corner box
    // from it directly so the morph works wherever the clock is parented (no `root` handle needed).
    private val parent get() = clock.parent as View
    private val marginPx get() = 24f * clock.resources.displayMetrics.density

    fun apply(state: VisualState, animate: Boolean) {
        if (state == current) return
        current = state
        clock.post { if (state == VisualState.ACTIVE) showActive(animate) else showIdle(animate) }
    }

    /**
     * Snap to the IDLE layout with no animation and mark IDLE as current, so the next
     * animated apply(ACTIVE) replays the full bloom (the state==current guard won't skip it).
     * Used when returning to the dashboard from the screensaver: we reset under the still-opaque
     * overlay, then animate, so the morph plays in sync with the overlay crossfade.
     * Must be called on the main thread (it mutates Views synchronously, unlike apply()'s posted body).
     *
     * [showMesh] = false keeps the ambient mesh hidden through the reset (and thus the whole morph),
     * so returning from a mesh-less saver (OLED) doesn't flash the mesh's colors over the dark exit.
     */
    fun resetToIdleInstant(showMesh: Boolean = true) {
        current = VisualState.IDLE
        showIdle(animate = false)
        if (!showMesh) {
            // showIdle() queued a duration-0 alpha→1 on the mesh that would otherwise apply next
            // frame and clobber our 0 — cancel it so the mesh stays hidden through the whole morph
            // (returning from the mesh-less OLED saver must not flash the mesh's colors).
            mesh.animate().cancel()
            mesh.stop()
            mesh.alpha = 0f
        }
    }

    /**
     * Host visibility hooks. The mesh should drift only when it's actually visible — i.e.
     * on-screen AND idle — so the host calls these from onStart/onStop instead of poking the
     * mesh directly (which would restart it even in the playing state, where it's hidden).
     */
    fun onVisible() { if (current == VisualState.IDLE) mesh.start() else mesh.stop() }
    fun onHidden() { mesh.stop() }

    private fun showActive(animate: Boolean) {
        val (tx, ty) = clockCornerTranslation()
        val dur = if (animate) 900L else 0L

        // Freeze the mesh immediately (it's about to be hidden), then fade it out — no point
        // burning CPU/GPU redrawing an invisible mesh behind the wash on an always-on screen.
        mesh.stop()
        mesh.animate().alpha(0f).setDuration(dur).start()
        wash.alpha = if (animate) 0f else 1f
        scrim.alpha = if (animate) 0f else 1f
        wash.animate().alpha(1f).setDuration(if (animate) 1000L else 0L).start()
        scrim.animate().alpha(1f).setDuration(dur).start()

        clock.animate().translationX(tx).translationY(ty).scaleX(cornerScale).scaleY(cornerScale)
            .setDuration(dur).setInterpolator(DecelerateInterpolator()).start()

        idleViews.forEach { it.animate().alpha(0f).setDuration(if (animate) 300L else 0L).start() }

        activeViews.forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            v.alpha = if (animate) 0f else 1f
            v.translationY = if (animate) dpToPx(16f) else 0f
            val delay = if (animate) 220L + i * 130L else 0L
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                .setDuration(if (animate) 520L else 0L).start()
        }
    }

    private fun showIdle(animate: Boolean) {
        val dur = if (animate) 700L else 0L
        mesh.start() // resume drifting as the mesh fades back in
        mesh.animate().alpha(1f).setDuration(dur).start()
        wash.animate().alpha(0f).setDuration(dur).start()
        scrim.animate().alpha(0f).setDuration(dur).start()
        clock.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(dur).setInterpolator(DecelerateInterpolator()).start()
        idleViews.forEach { it.visibility = View.VISIBLE; it.animate().alpha(1f).setDuration(dur).start() }
        activeViews.forEach { v ->
            v.animate().alpha(0f).setDuration(if (animate) 200L else 0L)
                .withEndAction { v.visibility = View.GONE }.start()
        }
    }

    /**
     * Parks the centered clock in the top-right corner of the content box. Settings/info now
     * live bottom-right, so the clock no longer has to dodge them — it targets the padded
     * content edge directly (paddings keep it inside the system-bar insets).
     */
    private fun clockCornerTranslation(): Pair<Float, Float> =
        BloomGeometry.cornerTranslation(
            parentWidth = parent.width,
            parentPaddingRight = parent.paddingRight,
            parentPaddingTop = parent.paddingTop,
            // Use the layout edges (translation-free), NOT clock.x/y. The shell clock now persists
            // across feature switches, so it can carry a leftover park/active transform when this
            // runs; the corner translation is absolute (set, not added), so feeding it the already-
            // translated center would land the clock short of the corner. left/top are the clean
            // baseline, and equal clock.x/y in the common translationX==0 case.
            clockX = clock.left.toFloat(),
            clockY = clock.top.toFloat(),
            clockWidth = clock.width,
            clockHeight = clock.height,
            cornerScale = cornerScale,
            marginPx = marginPx,
        )

    private fun dpToPx(dp: Float): Float = dp * clock.resources.displayMetrics.density
}
