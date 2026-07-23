package dev.rusty.app

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * One collapsible settings section: a focusable header row (view_settings_section_header.xml)
 * toggling a body container VISIBLE/GONE. A GONE body's children drop out of D-pad focus order
 * automatically, so a collapsed section is exactly one focus stop and needs no focus wiring.
 * Expanding never moves focus — it stays on the header; D-pad down enters the body.
 * State is per-instance (per panel bind), deliberately not persisted: panels start collapsed on
 * every visit except where the caller's attention rule says otherwise (unconfigured server).
 */
class CollapsibleSection(
    private val header: View,
    private val body: View,
    title: String,
    startExpanded: Boolean,
) {
    private val summaryView = header.findViewById<TextView>(R.id.tvSectionSummary)
    private val chevron = header.findViewById<TextView>(R.id.tvSectionChevron)
    private val avatar = header.findViewById<TextView>(R.id.tvSectionAvatar)

    var expanded: Boolean = startExpanded
        set(value) {
            field = value
            render()
        }

    init {
        header.findViewById<TextView>(R.id.tvSectionTitle).text = title
        header.setOnClickListener { expanded = !expanded }
        render()
    }

    fun setSummary(summary: SectionSummary) {
        summaryView.text = summary.text
        summaryView.setTextColor(
            ContextCompat.getColor(
                header.context,
                if (summary.active) R.color.accent_fallback else R.color.muted_dim,
            ),
        )
    }

    /** Small avatar before the title (HA Server when signed in). null hides it. */
    fun setAvatar(initial: String?) {
        avatar.visibility = if (initial == null) View.GONE else View.VISIBLE
        avatar.text = initial.orEmpty()
    }

    private fun render() {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 90f else 0f
    }
}
