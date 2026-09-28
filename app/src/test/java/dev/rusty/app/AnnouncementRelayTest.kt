package dev.rusty.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementRelayTest {

    @After fun tearDown() = AnnouncementRelay.resetForTest()

    @Test fun nothingIsShowingBeforeAnythingIsAnnounced() {
        assertNull(AnnouncementRelay.current())
    }

    @Test fun publishCarriesTheTextAndTheMomentItWasHandedOff() {
        AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)

        val current = AnnouncementRelay.current()!!
        assertEquals("Dinner is ready", current.text)
        assertEquals(5_000L, current.publishedAtMs)
        assertFalse(current.settled)
    }

    @Test fun publishNotifiesListeners() {
        var notified = 0
        val listener = { notified++; Unit }
        AnnouncementRelay.addListener(listener)

        AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)

        assertEquals(1, notified)
    }

    @Test fun settleMarksTheAnnouncementFinishedAndNotifies() {
        var notified = 0
        val id = AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)
        AnnouncementRelay.addListener { notified++ }

        AnnouncementRelay.settle(id)

        assertTrue(AnnouncementRelay.current()!!.settled)
        assertEquals(1, notified)
    }

    /** The previous clip's pipeline settling must not pull down the card of the one after it. */
    @Test fun aSettleForASupersededAnnouncementIsIgnored() {
        val first = AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)
        AnnouncementRelay.publish("Actually, ten more minutes", nowMs = 6_000)

        AnnouncementRelay.settle(first)

        assertFalse(AnnouncementRelay.current()!!.settled)
    }

    /** The same words twice in a row are two announcements, not one — each gets its own dwell. */
    @Test fun repeatingTheSameTextStartsAFreshAnnouncement() {
        val first = AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)
        val second = AnnouncementRelay.publish("Dinner is ready", nowMs = 9_000)

        assertNotEquals(first, second)
        assertEquals(9_000L, AnnouncementRelay.current()!!.publishedAtMs)
    }

    @Test fun aRemovedListenerStopsHearingAboutAnnouncements() {
        var notified = 0
        val listener = { notified++; Unit }
        AnnouncementRelay.addListener(listener)
        AnnouncementRelay.removeListener(listener)

        AnnouncementRelay.publish("Dinner is ready", nowMs = 5_000)

        assertEquals(0, notified)
    }
}
