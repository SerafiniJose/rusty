package dev.rusty.app

/** Coarse "how long has this camera been offline" text for the grid tile and the settings row.
 *  Coarse on purpose: the value only has to change when the reader would care, which is what
 *  keeps the tile repaint quiet (see CameraFragment's tick). */
object OfflineText {
    fun duration(elapsedMs: Long): String {
        val s = (elapsedMs / 1000L).coerceAtLeast(0L)
        return when {
            s < 60 -> "$s s"
            s < 3_600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3_600} h"
            else -> "${s / 86_400} d"
        }
    }
}
