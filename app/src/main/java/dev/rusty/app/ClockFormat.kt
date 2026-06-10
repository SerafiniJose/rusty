package dev.rusty.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, locale-aware clock formatting for the ambient face. Minute resolution only
 * (no seconds) to minimize always-on redraws. Kept Android-free for unit testing;
 * the Activity supplies device locale, 12/24h preference, and the default time zone.
 */
object ClockFormat {
    fun time(epochMillis: Long, is24Hour: Boolean, locale: Locale, zone: TimeZone): String {
        val pattern = if (is24Hour) "H:mm" else "h:mm"
        return format(pattern, epochMillis, locale, zone)
    }

    fun date(epochMillis: Long, locale: Locale, zone: TimeZone): String =
        format("EEEE · MMMM d", epochMillis, locale, zone)

    private fun format(pattern: String, epochMillis: Long, locale: Locale, zone: TimeZone): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(epochMillis))
}
