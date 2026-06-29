package dev.rusty.app

import android.content.Context
import android.graphics.Typeface

/**
 * Loads and caches the bundled Material Design Icons webfont + its name→codepoint map (from
 * `assets/mdi/`), and resolves an HA `mdi:`/`hass:` icon string to a renderable glyph.
 *
 * The font covers the full MDI set (~7.4k icons), so any dashboard's `mdi:` icon renders correctly
 * instead of falling back to a generic box. Non-MDI icons (e.g. `hacs:hacs`, brand glyphs absent from
 * core MDI) resolve to `null` here and the caller falls back to a bundled vector ([HaIcons]).
 *
 * Both the Typeface and the parsed map are loaded once (lazily, on first use) and cached process-wide.
 */
object MdiFont {
    private const val FONT_ASSET = "mdi/materialdesignicons-webfont.ttf"
    private const val CODEPOINTS_ASSET = "mdi/mdi-codepoints.txt"

    @Volatile private var typeface: Typeface? = null
    @Volatile private var codepoints: Map<String, Int>? = null

    /** The MDI [Typeface], loaded once from assets. */
    fun typeface(context: Context): Typeface =
        typeface ?: synchronized(this) {
            typeface ?: Typeface.createFromAsset(context.applicationContext.assets, FONT_ASSET)
                .also { typeface = it }
        }

    private fun codepoints(context: Context): Map<String, Int> =
        codepoints ?: synchronized(this) {
            codepoints ?: context.applicationContext.assets.open(CODEPOINTS_ASSET).bufferedReader().use { r ->
                MdiCodepoints.parse(r.lineSequence())
            }.also { codepoints = it }
        }

    /**
     * Returns the glyph string for an HA icon (e.g. `"mdi:baby-face-outline"` → the single-codepoint
     * String to draw with [typeface]), or `null` if the icon is not a known MDI glyph.
     */
    fun glyphFor(context: Context, icon: String?): String? {
        val cp = MdiCodepoints.codepointFor(codepoints(context), icon) ?: return null
        return String(Character.toChars(cp))
    }
}
