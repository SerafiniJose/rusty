package dev.rusty.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Builds the About card's What's new from [ReleaseNotes] sections: a coloured heading per section
 * ("ADDED 4") and one row per entry, its first sentence always and the rest once [showDetails].
 * On a wide screen the sections share two columns ([ReleaseNotes.splitColumns]) so a typical
 * release fits without scrolling.
 */
object WhatsNewView {

    private const val COLUMN_GAP_DP = 28

    fun bind(
        context: Context,
        container: LinearLayout,
        sections: List<ReleaseNotes.Section>,
        showDetails: Boolean,
        twoColumns: Boolean,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val (left, right) = if (twoColumns) ReleaseNotes.splitColumns(sections) else sections to emptyList()
        val gap = (COLUMN_GAP_DP * context.resources.displayMetrics.density).toInt()

        listOf(left, right).filter { it.isNotEmpty() }.forEachIndexed { index, columnSections ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0 && right.isNotEmpty()) marginEnd = gap
                }
            }
            columnSections.forEach { section -> column.addView(sectionView(context, inflater, column, section, showDetails)) }
            container.addView(column)
        }
    }

    private fun sectionView(
        context: Context,
        inflater: LayoutInflater,
        parent: LinearLayout,
        section: ReleaseNotes.Section,
        showDetails: Boolean,
    ): View {
        val view = inflater.inflate(R.layout.item_whats_new_section, parent, false) as LinearLayout
        val heading = view.findViewById<View>(R.id.rowSectionHeading)
        if (section.name.isEmpty()) {
            heading.visibility = View.GONE
        } else {
            val color = ContextCompat.getColor(context, colorFor(section.name))
            view.findViewById<View>(R.id.viewSectionMark).backgroundTintList = ColorStateList.valueOf(color)
            view.findViewById<TextView>(R.id.tvSectionName).apply {
                text = section.name
                setTextColor(color)
            }
            view.findViewById<TextView>(R.id.tvSectionCount).text = section.entries.size.toString()
        }
        section.entries.forEach { entry ->
            val row = inflater.inflate(R.layout.item_whats_new_entry, view, false)
            row.findViewById<TextView>(R.id.tvEntryTitle).text = entry.title
            val detail = row.findViewById<TextView>(R.id.tvEntryDetail)
            val detailShown = showDetails && entry.detail.isNotEmpty()
            detail.text = entry.detail
            detail.visibility = if (detailShown) View.VISIBLE else View.GONE
            // Stepping through entries is how a remote reads a list taller than the card.
            row.isFocusable = showDetails
            view.addView(row)
        }
        return view
    }

    private fun colorFor(name: String): Int = when (name.lowercase()) {
        "added" -> R.color.accent_fallback
        "changed" -> R.color.whats_new_changed
        "optimizations", "optimisations", "performance" -> R.color.whats_new_optimized
        "fixed" -> R.color.whats_new_fixed
        else -> R.color.muted_dim
    }
}
