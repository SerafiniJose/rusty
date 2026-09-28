package dev.rusty.app.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real AudioTrack: needs a device (there is no emulator on the build host).
 * Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.rusty.app.audio.PcmOutputTest`
 */
@RunWith(AndroidJUnit4::class)
class PcmOutputTest {

    private fun silence(seconds: Double): FloatArray =
        FloatArray((PcmOutput.SAMPLE_RATE * seconds).toInt() * PcmOutput.CHANNELS)

    @Test
    fun opensWritesPausesResumesAndReleases() {
        val out = PcmOutput()
        try {
            assertTrue(
                "buffer should hold ~1 s, got ${out.bufferFrames} frames",
                out.bufferFrames >= PcmOutput.SAMPLE_RATE * 9 / 10,
            )
            val quarter = silence(0.25)
            out.play()
            assertEquals(quarter.size, out.write(quarter, quarter.size))
            SystemClock.sleep(50)
            out.pause()
            out.play()
            assertEquals(quarter.size, out.write(quarter, quarter.size))
            assertTrue(out.underrunCount() >= 0)
        } finally {
            out.release()
        }
    }

    @Test
    fun blockingWriteIsTheBackpressure() {
        // 3 s of audio into a ~1 s buffer while playing: the write cannot return before ~2 s
        // have actually played. This is the property the Rust sink relies on instead of polling.
        val out = PcmOutput()
        try {
            out.play()
            val started = SystemClock.elapsedRealtime()
            val three = silence(3.0)
            assertEquals(three.size, out.write(three, three.size))
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("write returned after ${elapsed} ms; expected >= 1500 ms of blocking", elapsed >= 1_500)
        } finally {
            out.release()
        }
    }
}
