package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererMixModeTest {

    private val media = RendererMedia("http://x/a.mp3", "", "audio/mpeg")
    private fun playing(owner: SpotifyInterruption?) = RendererState(
        transport = RendererTransport.PLAYING, media = media, mediaGeneration = 1,
        spotifyInterruption = owner, spotifySessionGeneration = 7,
    )

    /**
     * The exact delay before Spotify is released IS the requirement, so pin it — in both modes and
     * from every path that can arm the release. 1s: long enough to bridge the gap between the parts
     * of a chained HA announcement, short enough that the user does not sit through a second of
     * silence (PAUSE) or of needlessly quiet music (DUCK) once the message has finished.
     */
    @Test
    fun `Spotify is released 1s after the announcement stops, in both modes, from every arming path`() {
        assertEquals(1_000L, ResumeGrace.MS)

        for (owner in listOf(SpotifyInterruption.PAUSE, SpotifyInterruption.DUCK)) {
            val armings = listOf(
                "end of stream" to reduceRenderer(playing(owner), RendererEvent.PlayerEnded(1)).second,
                "SOAP Stop" to reduceRenderer(playing(owner), RendererEvent.SoapStop).second,
                "player error" to reduceRenderer(playing(owner), RendererEvent.PlayerError(1, "boom")).second,
                "focus lost" to reduceRenderer(playing(owner), RendererEvent.FocusLost).second,
                "abandoned SetUri" to reduceRenderer(
                    playing(owner), RendererEvent.SoapSetUri("http://x/b.mp3", "", "audio/mpeg")).second,
            )
            armings.forEach { (path, fx) ->
                val timer = fx.filterIsInstance<RendererEffect.ScheduleResumeTimer>().single()
                assertEquals("$owner release armed by $path", 1_000L, timer.delayMs)
            }
        }
    }

    @Test
    fun `duck mode play against playing Spotify emits DuckSpotify and records the DUCK owner`() {
        val start = RendererState(media = media, mediaGeneration = 1)
        val (s, fx) = reduceRenderer(start, RendererEvent.SoapPlay(true, 7, SpotifyInterruption.DUCK))
        assertTrue(RendererEffect.DuckSpotify(0) in fx)
        assertTrue(RendererEffect.PauseSpotify !in fx)
        assertEquals(SpotifyInterruption.DUCK, s.spotifyInterruption)
    }

    @Test
    fun `no ownership taken when Spotify was not playing`() {
        val start = RendererState(media = media, mediaGeneration = 1)
        val (s, fx) = reduceRenderer(start, RendererEvent.SoapPlay(false, 7, SpotifyInterruption.DUCK))
        assertTrue(fx.none { it == RendererEffect.DuckSpotify(0) || it == RendererEffect.PauseSpotify })
        assertNull(s.spotifyInterruption)
    }

    @Test
    fun `end-of-stream grace releases a DUCK owner with RestoreSpotifyVolume — regardless of the guards`() {
        val (armed, _) = reduceRenderer(playing(SpotifyInterruption.DUCK), RendererEvent.PlayerEnded(1))
        assertTrue(armed.resumePending)
        // receiverPlaying=true and a mismatched session would SUPPRESS a PAUSE resume; DUCK restores anyway.
        val (s, fx) = reduceRenderer(armed, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 99))
        assertTrue(RendererEffect.RestoreSpotifyVolume(0) in fx)
        assertNull(s.spotifyInterruption)
    }

    @Test
    fun `PAUSE owner keeps the resume guards`() {
        val (armed, _) = reduceRenderer(playing(SpotifyInterruption.PAUSE), RendererEvent.PlayerEnded(1))
        val (_, fx) = reduceRenderer(armed, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 7))
        assertTrue("user already resumed — do not resume again", RendererEffect.ResumeSpotify !in fx)
    }

    /**
     * Regression: in DUCK mode Spotify is never stopped, so "Spotify started playing" is
     * indistinguishable from "Spotify never stopped". librespot publishes LOADING on every track
     * load that was not preloaded (auto-advance with an incomplete preload, or the user tapping
     * Next), ReceiverStateStore maps LOADING to playing=false, and the very next PLAYING is a
     * false→true edge — a SPURIOUS SpotifyStartedPlaying in the middle of the announcement.
     * Rule 12 used to treat that as the user reaching for Spotify: it stopped the player and
     * restored the volume, truncating a 30 s TTS message at the first track change and un-ducking
     * Spotify early. A DUCK owner must ignore the event outright.
     */
    @Test
    fun `SpotifyStartedPlaying with a DUCK owner is ignored — a track change is not a resume`() {
        val announcing = playing(SpotifyInterruption.DUCK)
        val (s, fx) = reduceRenderer(announcing, RendererEvent.SpotifyStartedPlaying(7))

        assertEquals("the announcement must keep playing", RendererTransport.PLAYING, s.transport)
        assertTrue("the announcement must not be stopped", RendererEffect.StopPlayer !in fx)
        assertTrue("the duck must not be released mid-announcement", RendererEffect.RestoreSpotifyVolume(0) !in fx)
        assertEquals("the DUCK debt is still owed", SpotifyInterruption.DUCK, s.spotifyInterruption)
        assertTrue(fx.isEmpty())
    }

    /** …and the debt survives: the duck is released by the stop path, exactly once, on the timer. */
    @Test
    fun `a DUCK owner ignoring a spurious start still releases at end of stream`() {
        val (ignored, _) = reduceRenderer(playing(SpotifyInterruption.DUCK), RendererEvent.SpotifyStartedPlaying(7))
        val (armed, _) = reduceRenderer(ignored, RendererEvent.PlayerEnded(1))
        assertTrue(armed.resumePending)
        val (released, fx) = reduceRenderer(armed, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 7))
        assertTrue(RendererEffect.RestoreSpotifyVolume(0) in fx)
        assertNull(released.spotifyInterruption)
    }

    /** A spurious start while the release is already armed must not cancel the timer either — that
     *  timer is the DUCK owner's only release. */
    @Test
    fun `SpotifyStartedPlaying while a DUCK release is armed keeps the timer`() {
        val (armed, _) = reduceRenderer(playing(SpotifyInterruption.DUCK), RendererEvent.SoapStop)
        assertTrue(armed.resumePending)
        val (s, fx) = reduceRenderer(armed, RendererEvent.SpotifyStartedPlaying(7))
        assertTrue("cancelling the timer would strand Spotify at duck volume", fx.isEmpty())
        assertTrue(s.resumePending)
        assertEquals(SpotifyInterruption.DUCK, s.spotifyInterruption)
    }

    @Test
    fun `SpotifyStartedPlaying with a PAUSE owner stays resume-free`() {
        val (_, fx) = reduceRenderer(playing(SpotifyInterruption.PAUSE), RendererEvent.SpotifyStartedPlaying(7))
        // No explicit ResumeSpotify — Spotify starting IS the user's resume — but the mixer it
        // resumed into may still be muted, so the volume restore is unconditional.
        assertTrue(RendererEffect.ResumeSpotify !in fx)
        assertTrue(RendererEffect.RestoreSpotifyVolume(0) in fx)
    }

    @Test
    fun `abandoned SetUri releases Spotify after the grace period in both modes`() {
        for (owner in listOf(SpotifyInterruption.PAUSE, SpotifyInterruption.DUCK)) {
            val (replaced, fx) = reduceRenderer(
                playing(owner), RendererEvent.SoapSetUri("http://x/b.mp3", "", "audio/mpeg"))
            assertTrue("SetUri while owning must re-arm ($owner)", RendererEffect.ScheduleResumeTimer(ResumeGrace.MS) in fx)
            assertTrue(replaced.resumePending)
            assertEquals("debt preserved across replacement", owner, replaced.spotifyInterruption)

            val (released, releaseFx) = reduceRenderer(
                replaced, RendererEvent.ResumeTimerFired(receiverPlaying = false, receiverSessionGen = 7))
            val expected = if (owner == SpotifyInterruption.PAUSE) RendererEffect.ResumeSpotify
                           else RendererEffect.RestoreSpotifyVolume(0)
            assertTrue("no Play ever came — Spotify must be released ($owner)", expected in releaseFx)
            assertNull(released.spotifyInterruption)
        }
    }

    @Test
    fun `Play within the grace window cancels the re-armed timer — the normal announcement chain`() {
        val (replaced, _) = reduceRenderer(
            playing(SpotifyInterruption.DUCK), RendererEvent.SoapSetUri("http://x/b.mp3", "", "audio/mpeg"))
        val (s, fx) = reduceRenderer(replaced, RendererEvent.SoapPlay(false, 7, SpotifyInterruption.DUCK))
        assertTrue(RendererEffect.CancelResumeTimer in fx)
        assertTrue("ownership already held — no double duck", fx.none { it == RendererEffect.DuckSpotify(0) })
        assertEquals(SpotifyInterruption.DUCK, s.spotifyInterruption)
    }

    @Test
    fun `release branches on the recorded owner even when the event-stamped mode has changed`() {
        // Ownership was taken as DUCK; a later Play stamped PAUSE must not flip the owner.
        val (replaced, _) = reduceRenderer(
            playing(SpotifyInterruption.DUCK), RendererEvent.SoapSetUri("http://x/b.mp3", "", "audio/mpeg"))
        val (s, _) = reduceRenderer(replaced, RendererEvent.SoapPlay(false, 7, SpotifyInterruption.PAUSE))
        assertEquals(SpotifyInterruption.DUCK, s.spotifyInterruption)
        val (_, fx) = reduceRenderer(s, RendererEvent.Shutdown(receiverPlaying = false, receiverSessionGen = 7))
        assertTrue(RendererEffect.RestoreSpotifyVolume(0) in fx)
    }

    @Test
    fun `Shutdown with a DUCK owner restores`() {
        val (_, fx) = reduceRenderer(playing(SpotifyInterruption.DUCK),
            RendererEvent.Shutdown(receiverPlaying = true, receiverSessionGen = 99))
        assertTrue(RendererEffect.RestoreSpotifyVolume(0) in fx)
    }
}
