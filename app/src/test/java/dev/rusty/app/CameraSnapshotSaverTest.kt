package dev.rusty.app

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSnapshotSaverTest {
    @Test
    fun `file name slugs the camera and stamps local time`() {
        val t = 1_788_556_440_000L // 2026-09-04 21:14:00 UTC
        assertEquals("front-door_20260904_211400.jpg", CameraSnapshotSaver.fileName("Front Door!", t, TimeZone.getTimeZone("UTC")))
        assertEquals("camera_20260904_211400.jpg", CameraSnapshotSaver.fileName("  ", t, TimeZone.getTimeZone("UTC")))
    }
}
