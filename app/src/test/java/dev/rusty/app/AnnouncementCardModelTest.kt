package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementCardModelTest {

    @Test fun hiddenWhenNothingWasAnnounced() {
        assertFalse(AnnouncementCardModel.visible(publishedAtMs = null, settled = false, nowMs = 1_000))
    }

    @Test fun visibleWhileTheDeviceIsStillSpeaking() {
        assertTrue(AnnouncementCardModel.visible(publishedAtMs = 1_000, settled = false, nowMs = 9_000))
    }

    @Test fun staysUpForTheMinimumDwellWhenTheClipEndsAlmostAtOnce() {
        // "Dinner!" — a clip so short the pipeline settles before anyone could read the card.
        assertTrue(AnnouncementCardModel.visible(publishedAtMs = 1_000, settled = true, nowMs = 1_400))
    }

    @Test fun leavesOncePlaybackSettledAndTheMinimumDwellPassed() {
        val now = 1_000 + AnnouncementCardModel.MIN_DWELL_MS
        assertFalse(AnnouncementCardModel.visible(publishedAtMs = 1_000, settled = true, nowMs = now))
    }

    @Test fun leavesAtTheCeilingEvenIfPlaybackNeverSettles() {
        val now = 1_000 + AnnouncementCardModel.MAX_DWELL_MS
        assertFalse(AnnouncementCardModel.visible(publishedAtMs = 1_000, settled = false, nowMs = now))
    }

    /** The view can only re-check on a timer, so the model says when the next check is due. */
    @Test fun nextCheckIsTheMinimumDwellWhilePlaybackIsStillRunning() {
        assertEquals(
            AnnouncementCardModel.MIN_DWELL_MS,
            AnnouncementCardModel.nextCheckDelayMs(publishedAtMs = 1_000, settled = false, nowMs = 1_000),
        )
    }

    @Test fun nextCheckIsTheCeilingOnceTheMinimumDwellIsBehindUs() {
        assertEquals(
            AnnouncementCardModel.MAX_DWELL_MS - AnnouncementCardModel.MIN_DWELL_MS,
            AnnouncementCardModel.nextCheckDelayMs(
                publishedAtMs = 1_000,
                settled = false,
                nowMs = 1_000 + AnnouncementCardModel.MIN_DWELL_MS,
            ),
        )
    }

    @Test fun shortAnnouncementsKeepTheFullTextSize() {
        assertEquals(AnnouncementCardModel.TEXT_SIZE_SP, AnnouncementCardModel.textSizeSp(lineCount = 3), 0f)
    }

    @Test fun longAnnouncementsStepDownASize() {
        assertEquals(AnnouncementCardModel.TEXT_SIZE_COMPACT_SP, AnnouncementCardModel.textSizeSp(lineCount = 4), 0f)
    }
}
