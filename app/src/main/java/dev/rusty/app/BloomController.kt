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
    private val root: View,
    private val clock: TextView,
    private val idleViews: List<View>,    // date, status
    private val activeViews: List<View>,  // identitySuffix, albumArtCard, playingInfo (children animate transitively)
    private val mesh: AmbientMeshView,
    private val wash: View,
    private val scrim: View
) {
    private var current: VisualState? = null
    private val cornerScale = 0.22f
    private val marginPx get() = 24f * root.resources.displayMetrics.density

    fun apply(state: VisualState, animate: Boolean) {
        if (state == current) return
        current = state
        root.post { if (state == VisualState.ACTIVE) showActive(animate) else showIdle(animate) }
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
    private fun clockCornerTranslation(): Pair<Float, Float> {
        val startCx = clock.x + clock.width / 2f
        val startCy = clock.y + clock.height / 2f
        val rightEdge = root.width - root.paddingRight - marginPx
        val topEdge = root.paddingTop + marginPx
        val targetCx = rightEdge - (clock.width * cornerScale) / 2f
        val targetCy = topEdge + (clock.height * cornerScale) / 2f
        return (targetCx - startCx) to (targetCy - startCy)
    }

    private fun dpToPx(dp: Float): Float = dp * root.resources.displayMetrics.density
}
