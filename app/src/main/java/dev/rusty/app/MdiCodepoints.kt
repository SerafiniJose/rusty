package dev.rusty.app

/**
 * Pure parser/resolver for the bundled Material Design Icons codepoint map
 * (`assets/mdi/mdi-codepoints.txt`, one `name HEXCODEPOINT` pair per line, e.g. `map F034D`).
 *
 * Kept Android-free so the parsing + HA icon-name normalization are unit-testable on the JVM; the
 * Typeface load and glyph rendering live in [MdiFont] / [MdiGlyphDrawable].
 */
object MdiCodepoints {

    /** Parses `name HEX` lines into a name→codepoint map. Blank and malformed lines are skipped. */
    fun parse(lines: Sequence<String>): Map<String, Int> {
        val out = HashMap<String, Int>(8192)
        for (line in lines) {
            val parts = line.trim().split(' ')
            if (parts.size != 2) continue
            val name = parts[0]
            val cp = parts[1].toIntOrNull(16) ?: continue
            if (name.isNotEmpty()) out[name] = cp
        }
        return out
    }

    /**
     * Normalizes an HA icon string to a bare MDI name: strips the `mdi:`/`hass:` prefix (HA uses
     * `hass:` as a historical alias for `mdi:`) and trims. Returns `null` for null/blank/prefix-only.
     */
    fun normalizeName(icon: String?): String? {
        if (icon == null) return null
        var s = icon.trim()
        if (s.startsWith("mdi:")) s = s.removePrefix("mdi:").trim()
        else if (s.startsWith("hass:")) s = s.removePrefix("hass:").trim()
        return s.ifEmpty { null }
    }

    /** Resolves an HA icon string to a codepoint via [normalizeName], or `null` if not an MDI glyph. */
    fun codepointFor(map: Map<String, Int>, icon: String?): Int? =
        normalizeName(icon)?.let { map[it] }
}
