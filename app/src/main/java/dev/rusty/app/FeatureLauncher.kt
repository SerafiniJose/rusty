package dev.rusty.app

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout

/**
 * One pill in the expandable launcher: an icon, its accessibility label, whether it marks the
 * currently-active page (accent-tinted), and the action to run when chosen. The launcher collapses
 * itself before invoking [onSelect].
 */
data class LauncherEntry(
    val iconRes: Int,
    val label: String,
    val active: Boolean,
    val onSelect: () -> Unit,
)

/**
 * Reusable expandable launcher used by both the shell chrome and the screensaver chrome. A [toggle]
 * button fans a vertical column of icon pills upward out of [menu], with a tap-catching [scrim], a
 * staggered open animation, and D-pad focus chaining. The pill set is supplied by [provideEntries]
 * and rebuilt on every open/refresh, so callers never touch the views directly.
 *
 * Back-button and key handling stay the host's concern — observe [isOpen], call [collapse], and wire
 * [onOpenChanged] (e.g. to enable an OnBackPressedCallback only while open).
 *
 * @param activeTint color filter for the active page's pill.
 * @param inactiveTint color filter for every other pill.
 * @param itemLayoutRes a single ImageButton pill layout (e.g. R.layout.view_launcher_item).
 * @param minEntriesToShow [toggle] is shown only when at least this many entries exist (1 = always
 *   visible for the shell, since Lock is always present; 2 for the saver, where a lone feature is
 *   nothing to navigate to).
 */
class FeatureLauncher(
    private val toggle: ImageButton,
    private val menu: LinearLayout,
    private val scrim: View,
    private val activeTint: Int,
    private val inactiveTint: Int,
    private val itemLayoutRes: Int,
    private val minEntriesToShow: Int,
    private val provideEntries: () -> List<LauncherEntry>,
) {
    var isOpen = false
        private set

    /** Invoked with the new open-state on every expand/collapse. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    private val defaultToggleFocusUp = toggle.nextFocusUpId

    init {
        toggle.setOnClickListener { toggle() }
        scrim.setOnClickListener { collapse() }
    }

    fun toggle() {
        if (isOpen) collapse() else expand()
    }

    /** Reconciles toggle visibility with the current entry count; rebuilds the menu if it's open. */
    fun refresh() {
        val entries = provideEntries()
        toggle.visibility = if (entries.size >= minEntriesToShow) View.VISIBLE else View.GONE
        if (isOpen) build(entries)
    }

    fun expand() {
        isOpen = true
        onOpenChanged?.invoke(true)
        build(provideEntries())
        scrim.visibility = View.VISIBLE
        menu.visibility = View.VISIBLE
        val n = menu.childCount
        for (i in 0 until n) {
            val child = menu.getChildAt(i)
            child.alpha = 0f
            child.translationY = 24f
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(((n - 1 - i) * 30).toLong())
                .setDuration(140).start()
        }
        if (!toggle.isInTouchMode && n > 0) menu.getChildAt(n - 1).requestFocus()
    }

    fun collapse() {
        menu.visibility = View.GONE
        scrim.visibility = View.GONE
        isOpen = false
        onOpenChanged?.invoke(false)
        toggle.nextFocusUpId = defaultToggleFocusUp
        if (!toggle.isInTouchMode) toggle.requestFocus()
    }

    /** Inflates one pill per entry (top-to-bottom as provided) and wires clicks + D-pad chaining. */
    private fun build(entries: List<LauncherEntry>) {
        menu.removeAllViews()
        val inflater = LayoutInflater.from(menu.context)
        entries.forEach { entry ->
            val pill = inflater.inflate(itemLayoutRes, menu, false) as ImageButton
            pill.id = View.generateViewId()
            pill.setImageResource(entry.iconRes)
            pill.contentDescription = entry.label
            pill.setColorFilter(if (entry.active) activeTint else inactiveTint)
            pill.setOnClickListener {
                collapse()
                entry.onSelect()
            }
            menu.addView(pill)
        }
        // Chain D-pad focus top-to-bottom; the top pill points Up at itself (no escape) and the
        // bottom pill points Down at the toggle, which points Up back into the bottom pill.
        val n = menu.childCount
        for (i in 0 until n) {
            val child = menu.getChildAt(i)
            child.nextFocusUpId = if (i > 0) menu.getChildAt(i - 1).id else child.id
            child.nextFocusDownId = if (i < n - 1) menu.getChildAt(i + 1).id else toggle.id
        }
        if (n > 0) toggle.nextFocusUpId = menu.getChildAt(n - 1).id
    }
}
