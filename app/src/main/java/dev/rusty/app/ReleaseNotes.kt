package dev.rusty.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure: turns a release's plain-text notes (already through [UpdateRepository.cleanNotes]) into
 * the sections the About sheet and the control page lay out — "Added", "Changed", "Fixed"… each a
 * list of entries split into a one-line [Entry.title] and the rest as [Entry.detail].
 *
 * The CHANGELOG is hard-wrapped at ~72 columns with two-space continuation lines, which read as a
 * ragged block once a narrower screen wraps them again; re-joining each bullet into one paragraph
 * is the main point of this parser. The notes themselves stay plain text (the app has no Markdown
 * renderer), so the structure is read from the conventions the CHANGELOG already follows.
 */
object ReleaseNotes {

    data class Entry(val title: String, val detail: String)

    /** [name] is empty for text that comes before any heading (then no heading row is drawn). */
    data class Section(val name: String, val entries: List<Entry>)

    private const val BULLET = "• "

    /** The first sentence (or clause ending in a colon) of an entry is its title. */
    private val titleSplit = Regex("""^(.+?)([.:])\s+(\S.*)$""")

    /**
     * Sections in order. A short line that is not a bullet, not indented and not a sentence starts
     * a new section when it opens the text or follows a blank line; any other line continues the
     * entry above it (an indented line always does) or, after a blank line, is an entry of its own.
     * Entries before the first heading form one unnamed section.
     */
    fun parse(notes: String): List<Section> {
        val sections = mutableListOf<Pair<String, MutableList<StringBuilder>>>()
        var current: MutableList<StringBuilder>? = null
        var entry: StringBuilder? = null
        var previousBlank = true

        for (raw in notes.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                previousBlank = true
                entry = null
                continue
            }
            val indented = line.first().isWhitespace()
            val trimmed = line.trim()
            when {
                trimmed.startsWith(BULLET) || trimmed == BULLET.trim() -> {
                    val list = current ?: mutableListOf<StringBuilder>().also {
                        sections += "" to it
                        current = it
                    }
                    entry = StringBuilder(trimmed.removePrefix(BULLET.trim()).trim()).also { list += it }
                }
                entry != null && (indented || !previousBlank) -> entry.append(' ').append(trimmed)
                previousBlank && !indented && isHeading(trimmed) -> {
                    val list = mutableListOf<StringBuilder>()
                    sections += trimmed.removeSuffix(":").trim() to list
                    current = list
                    entry = null
                }
                else -> {
                    // Prose inside a section, not in a bullet: its own entry.
                    val list = current ?: mutableListOf<StringBuilder>().also {
                        sections += "" to it
                        current = it
                    }
                    entry = StringBuilder(trimmed).also { list += it }
                }
            }
            previousBlank = false
        }

        return sections
            .map { (name, entries) -> Section(name, entries.map { splitEntry(it.toString()) }) }
            .filter { it.entries.isNotEmpty() }
    }

    /** "Added", "Fixed:" — short, and not a sentence. */
    private fun isHeading(line: String): Boolean =
        line.length <= MAX_HEADING_LENGTH && line.last() !in ".!?"

    private const val MAX_HEADING_LENGTH = 40

    /** "Seek from Rusty: drag the bar." → ("Seek from Rusty.", "Drag the bar."). */
    fun splitEntry(text: String): Entry {
        val m = titleSplit.find(text) ?: return Entry(text, "")
        val (head, mark, rest) = m.destructured
        val detail = if (mark == ":") rest.replaceFirstChar { it.uppercaseChar() } else rest
        return Entry("$head.", detail)
    }

    fun entryCount(sections: List<Section>): Int = sections.sumOf { it.entries.size }

    /**
     * Splits sections, in order, into two columns of about the same number of entries: the first
     * column takes sections while doing so brings it closer to half. Never splits a section.
     */
    fun splitColumns(sections: List<Section>): Pair<List<Section>, List<Section>> {
        if (sections.size < 2) return sections to emptyList()
        val half = entryCount(sections) / 2.0
        var taken = 0
        var count = 0
        for (section in sections) {
            val next = count + section.entries.size
            if (taken > 0 && abs(next - half) >= abs(count - half)) break
            count = next
            taken++
        }
        if (taken == sections.size) taken = sections.size - 1
        return sections.take(taken) to sections.drop(taken)
    }

    /** "4 added · 2 changed · 6 fixed"; unnamed sections are left out. */
    fun summary(sections: List<Section>): String =
        sections.filter { it.name.isNotEmpty() }
            .joinToString(" · ") { "${it.entries.size} ${it.name.lowercase(Locale.ROOT)}" }

    /**
     * "Released 21 Sep", or "Released 21 Sep 2025" outside the current year, from GitHub's
     * `published_at` (ISO-8601). Null when missing or unparseable. English month names, like the
     * rest of the app's copy.
     */
    fun releasedLabel(publishedAt: String?, nowMs: Long, zone: ZoneId): String? {
        if (publishedAt.isNullOrBlank()) return null
        val date = runCatching { Instant.parse(publishedAt).atZone(zone).toLocalDate() }.getOrNull()
            ?: return null
        val thisYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
        val pattern = if (date.year == thisYear) "d MMM" else "d MMM yyyy"
        return "Released " + date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }

    /** "75 MB" (decimal megabytes, as GitHub shows them); null for an unknown or empty size. */
    fun sizeLabel(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val mb = bytes / 1_000_000.0
        return if (mb < 1) "1 MB" else "${mb.roundToLong()} MB"
    }

    /**
     * "Checked today at 09:14", "Checked yesterday at 21:03" or "Checked 21 Sep at 09:14".
     * [timeText] is the check's time already formatted for the device's 12/24-hour setting.
     */
    fun checkedLabel(checkedAtMs: Long, nowMs: Long, zone: ZoneId, timeText: String): String {
        val day = Instant.ofEpochMilli(checkedAtMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dayText = when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        }
        return "Checked $dayText at $timeText"
    }
}
