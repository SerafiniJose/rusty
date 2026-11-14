package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWatchRepositoryTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `parses a track with the largest album image and joined artists`() {
        val json = """
            {
              "is_playing": true,
              "progress_ms": 61000,
              "currently_playing_type": "track",
              "device": { "name": "Kitchen", "type": "Speaker" },
              "item": {
                "type": "track",
                "id": "track-1",
                "name": "Night Drive",
                "duration_ms": 240000,
                "artists": [
                  { "name": "A" },
                  { "name": "B" }
                ],
                "album": {
                  "name": "After Hours",
                  "images": [
                    { "url": "https://img/small.jpg", "width": 64 },
                    { "url": "https://img/large.jpg", "width": 640 },
                    { "url": "https://img/medium.jpg", "width": 300 }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = SpotifyWatchRepository.parse(json, now)
        assertTrue(result is WatchResult.Playing)
        val track = (result as WatchResult.Playing).track
        assertEquals("track-1", track.trackId)
        assertEquals("Night Drive", track.title)
        assertEquals("A, B", track.artist)
        assertEquals("After Hours", track.album)
        assertEquals("https://img/large.jpg", track.artUrl)
        assertEquals("Kitchen", track.deviceName)
        assertEquals("Speaker", track.deviceType)
        assertEquals(240_000L, track.durationMs)
        assertEquals(61_000L, track.progressMs)
        assertTrue(track.isPlaying)
        assertEquals(false, track.isEpisode)
        assertEquals(61_000L, track.elapsedAt(now))
    }

    @Test
    fun `advances progress locally only while playing`() {
        val playing = track(progressMs = 10_000, isPlaying = true)
        val paused = track(progressMs = 10_000, isPlaying = false)
        assertEquals(15_000L, playing.elapsedAt(now + 5_000))
        assertEquals(10_000L, paused.elapsedAt(now + 5_000))
    }

    @Test
    fun `clamps local progress to the track duration`() {
        val track = track(progressMs = 9_000, isPlaying = true, durationMs = 10_000)
        assertEquals(10_000L, track.elapsedAt(now + 5_000))
    }

    @Test
    fun `parses an episode against the show name and episode image`() {
        val json = """
            {
              "is_playing": false,
              "progress_ms": 1200,
              "currently_playing_type": "episode",
              "device": { "name": "Phone", "type": "Smartphone" },
              "item": {
                "type": "episode",
                "id": "episode-12",
                "name": "Episode 12",
                "duration_ms": 1800000,
                "images": [
                  { "url": "https://img/show-64.jpg", "width": 64 },
                  { "url": "https://img/show-640.jpg", "width": 640 }
                ],
                "show": {
                  "name": "The Show",
                  "images": [
                    { "url": "https://img/fallback.jpg", "width": 300 }
                  ]
                }
              }
            }
        """.trimIndent()

        val track = (SpotifyWatchRepository.parse(json, now) as WatchResult.Playing).track
        assertEquals("episode-12", track.trackId)
        assertEquals("Episode 12", track.title)
        assertEquals("The Show", track.artist)
        assertEquals(null, track.album)
        assertEquals("https://img/show-640.jpg", track.artUrl)
        assertTrue(track.isEpisode)
        assertEquals(false, track.isPlaying)
    }

    @Test
    fun `a null item is nothing playing`() {
        val json = """{ "is_playing": false, "item": null }"""
        assertEquals(WatchResult.Nothing, SpotifyWatchRepository.parse(json, now))
    }

    @Test
    fun `an advert is nothing playing`() {
        val json = """{ "is_playing": true, "item": { "type": "ad", "name": "Advert" } }"""
        assertEquals(WatchResult.Nothing, SpotifyWatchRepository.parse(json, now))
    }

    private fun track(
        progressMs: Long,
        isPlaying: Boolean,
        durationMs: Long = 240_000,
    ) = WatchNowPlaying(
        trackId = "track-1",
        title = "Track",
        artist = "Artist",
        album = null,
        artUrl = null,
        progressMs = progressMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        deviceName = null,
        deviceType = null,
        isEpisode = false,
        receivedAtMs = now,
    )
}
