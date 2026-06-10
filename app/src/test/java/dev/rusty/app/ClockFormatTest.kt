package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ClockFormatTest {
    // 2021-01-05T09:41:00Z (a Tuesday) and 2021-01-05T15:05:00Z
    private val morning = 1609839660000L
    private val afternoon = 1609859100000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val us = Locale.US

    @Test fun formats24HourTime() {
        assertEquals("9:41", ClockFormat.time(morning, is24Hour = true, locale = us, zone = utc))
        assertEquals("15:05", ClockFormat.time(afternoon, is24Hour = true, locale = us, zone = utc))
    }

    @Test fun formats12HourTimeWithoutSeconds() {
        assertEquals("9:41", ClockFormat.time(morning, is24Hour = false, locale = us, zone = utc))
        assertEquals("3:05", ClockFormat.time(afternoon, is24Hour = false, locale = us, zone = utc))
    }

    @Test fun formatsDateWithWeekdayAndMonth() {
        assertEquals("Tuesday · January 5", ClockFormat.date(morning, locale = us, zone = utc))
    }
}
