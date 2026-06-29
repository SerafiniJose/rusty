package dev.rusty.app

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/** Android-side resolver: maps an HA `mdi:` icon string to a bundled vector drawable. The name→glyph
 *  mapping is pure ([HomeAssistantDashboards.glyphFor]); this object only owns the @DrawableRes table. */
object HaIcons {

    /**
     * Resolves an HA icon string to a [Drawable] for a chip OR a settings dashboard card. Prefers the
     * bundled full MDI font ([MdiFont]) so any `mdi:`/`hass:` icon renders correctly; falls back to the
     * curated vector set ([drawableFor], ultimately the generic box) for non-MDI/brand icons (e.g.
     * `hacs:hacs`). [sizePx] is the target icon size so the glyph drawable reports a matching intrinsic size.
     */
    fun iconDrawable(context: Context, mdi: String?, sizePx: Int): Drawable {
        MdiFont.glyphFor(context, mdi)?.let { glyph ->
            return MdiGlyphDrawable(MdiFont.typeface(context), glyph, sizePx)
        }
        return ContextCompat.getDrawable(context, drawableFor(mdi))!!
    }

    @DrawableRes
    fun drawableFor(mdi: String?): Int = when (HomeAssistantDashboards.glyphFor(mdi)) {
        HomeAssistantDashboards.HaGlyph.DASHBOARD -> R.drawable.ic_mdi_dashboard
        HomeAssistantDashboards.HaGlyph.MAP -> R.drawable.ic_mdi_map
        HomeAssistantDashboards.HaGlyph.LOGBOOK -> R.drawable.ic_mdi_logbook
        HomeAssistantDashboards.HaGlyph.HISTORY -> R.drawable.ic_mdi_history
        HomeAssistantDashboards.HaGlyph.ENERGY -> R.drawable.ic_mdi_energy
        HomeAssistantDashboards.HaGlyph.CALENDAR -> R.drawable.ic_mdi_calendar
        HomeAssistantDashboards.HaGlyph.TODO -> R.drawable.ic_mdi_todo
        HomeAssistantDashboards.HaGlyph.SETTINGS -> R.drawable.ic_mdi_settings
        HomeAssistantDashboards.HaGlyph.CHART -> R.drawable.ic_mdi_chart
        HomeAssistantDashboards.HaGlyph.GENERIC -> R.drawable.ic_mdi_generic
    }
}
