package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReleaseNotesTest {

    /** The shape the CHANGELOG ships, after [UpdateRepository.cleanNotes]. */
    private val notes = """
        Added
        • Share this camera. A Rusty with a camera can stream it to other Rusty
          devices, Home Assistant or VLC over plain RTSP.
        • Pinch to zoom the live camera picture, up to 4x, and drag to move around
          it. Touch only.

        Changed
        • Spoken announcements no longer need the DLNA player running.

        Fixed
        • Rusty is properly navigable with a TV remote again: the settings tab strip is
          visible and reachable with the D-pad.
        • The Slideshow server section starts collapsed like the others.
    """.trimIndent()

    // ---- parse --------------------------------------------------------------

    @Test
    fun readsSectionsInOrder() {
        val sections = ReleaseNotes.parse(notes)
        assertEquals(listOf("Added", "Changed", "Fixed"), sections.map { it.name })
        assertEquals(listOf(2, 1, 2), sections.map { it.entries.size })
    }

    @Test
    fun rejoinsHardWrappedLines() {
        val first = ReleaseNotes.parse(notes)[0].entries[0]
        assertEquals("Share this camera.", first.title)
        assertEquals(
            "A Rusty with a camera can stream it to other Rusty devices, Home Assistant or VLC over plain RTSP.",
            first.detail,
        )
    }

    @Test
    fun aWrapInsideTheFirstSentenceStaysInTheTitle() {
        val pinch = ReleaseNotes.parse(notes)[0].entries[1]
        assertEquals("Pinch to zoom the live camera picture, up to 4x, and drag to move around it.", pinch.title)
        assertEquals("Touch only.", pinch.detail)
    }

    @Test
    fun oneSentenceEntryHasNoDetail() {
        val entry = ReleaseNotes.parse(notes)[1].entries[0]
        assertEquals("Spoken announcements no longer need the DLNA player running.", entry.title)
        assertEquals("", entry.detail)
    }

    @Test
    fun colonEndsTheTitleAndCapitalisesTheDetail() {
        val entry = ReleaseNotes.parse(notes)[2].entries[0]
        assertEquals("Rusty is properly navigable with a TV remote again.", entry.title)
        assertEquals("The settings tab strip is visible and reachable with the D-pad.", entry.detail)
    }

    @Test
    fun versionNumbersAreNotSentenceEnds() {
        val entry = ReleaseNotes.splitEntry("androidx.media3 1.11: a stalled stream recovers.")
        assertEquals("androidx.media3 1.11.", entry.title)
        assertEquals("A stalled stream recovers.", entry.detail)
    }

    @Test
    fun bulletsBeforeAnyHeadingFormAnUnnamedSection() {
        val sections = ReleaseNotes.parse("• Start/stop control\n• Bug fixes")
        assertEquals(1, sections.size)
        assertEquals("", sections[0].name)
        assertEquals(listOf("Start/stop control", "Bug fixes"), sections[0].entries.map { it.title })
    }

    @Test
    fun plainProseBecomesEntries() {
        val sections = ReleaseNotes.parse("A small maintenance release.\n\nFaster startup.")
        assertEquals(1, sections.size)
        assertEquals(2, sections[0].entries.size)
    }

    @Test
    fun emptyNotesHaveNoSections() {
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertTrue(ReleaseNotes.parse("\n\n").isEmpty())
    }

    @Test
    fun headingWithNoEntriesIsDropped() {
        val sections = ReleaseNotes.parse("Added\n\nFixed\n• A fix.")
        assertEquals(listOf("Fixed"), sections.map { it.name })
    }

    // ---- columns / summary ----------------------------------------------------

    private fun section(name: String, n: Int) =
        ReleaseNotes.Section(name, List(n) { ReleaseNotes.Entry("e$it.", "") })

    @Test
    fun columnsBalanceWithoutSplittingASection() {
        val (left, right) = ReleaseNotes.splitColumns(
            listOf(section("Added", 5), section("Changed", 1), section("Optimizations", 3), section("Fixed", 4)),
        )
        assertEquals(listOf("Added", "Changed"), left.map { it.name })
        assertEquals(listOf("Optimizations", "Fixed"), right.map { it.name })
    }

    @Test
    fun oneBigSectionStillLeavesTheRestForTheSecondColumn() {
        val (left, right) = ReleaseNotes.splitColumns(listOf(section("Added", 12), section("Fixed", 1)))
        assertEquals(listOf("Added"), left.map { it.name })
        assertEquals(listOf("Fixed"), right.map { it.name })
    }

    @Test
    fun aSingleSectionTakesOneColumn() {
        val (left, right) = ReleaseNotes.splitColumns(listOf(section("Fixed", 3)))
        assertEquals(1, left.size)
        assertTrue(right.isEmpty())
    }

    @Test
    fun summaryCountsNamedSections() {
        assertEquals("4 added · 2 changed · 6 fixed",
            ReleaseNotes.summary(listOf(section("Added", 4), section("Changed", 2), section("Fixed", 6))))
        assertEquals("", ReleaseNotes.summary(listOf(section("", 3))))
    }

    // ---- labels ---------------------------------------------------------------

    private val rome = ZoneId.of("Europe/Rome")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, rome).toInstant().toEpochMilli()

    @Test
    fun releasedLabelUsesTheLocalDay() {
        // 22:18 UTC on the 20th is already the 21st in Rome.
        assertEquals("Released 21 Sep", ReleaseNotes.releasedLabel("2026-09-20T22:18:19Z", at(2026, 9, 28), rome))
    }

    @Test
    fun releasedLabelNamesTheYearOutsideThisOne() {
        assertEquals("Released 3 Dec 2025", ReleaseNotes.releasedLabel("2025-12-03T10:00:00Z", at(2026, 1, 5), rome))
    }

    @Test
    fun releasedLabelNullWhenMissingOrGarbage() {
        assertNull(ReleaseNotes.releasedLabel(null, at(2026, 9, 28), rome))
        assertNull(ReleaseNotes.releasedLabel("yesterday", at(2026, 9, 28), rome))
    }

    @Test
    fun sizeLabelInDecimalMegabytes() {
        assertEquals("75 MB", ReleaseNotes.sizeLabel(75_370_564))
        assertEquals("1 MB", ReleaseNotes.sizeLabel(20_000))
        assertNull(ReleaseNotes.sizeLabel(0))
        assertNull(ReleaseNotes.sizeLabel(null))
    }

    @Test
    fun checkedLabelSaysTodayYesterdayOrTheDate() {
        val now = at(2026, 9, 28, 18)
        assertEquals("Checked today at 09:14", ReleaseNotes.checkedLabel(at(2026, 9, 28, 9, 14), now, rome, "09:14"))
        assertEquals("Checked yesterday at 21:03", ReleaseNotes.checkedLabel(at(2026, 9, 27, 21, 3), now, rome, "21:03"))
        assertEquals("Checked 21 Sep at 08:00", ReleaseNotes.checkedLabel(at(2026, 9, 21, 8), now, rome, "08:00"))
    }
}
