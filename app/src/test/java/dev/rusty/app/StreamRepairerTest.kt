package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRepairerTest {
    @Test
    fun `the None repairer fails synchronously and caches nothing`() {
        var result: RepairResult? = null
        StreamRepairer.None.repair("rtsp://cam/x", "u", "p", true) { result = it }
        assertEquals(RepairResult.Failed("no repairer installed"), result)
        assertNull(StreamRepairer.None.cachedProxyUrl("rtsp://cam/x"))
    }
}
