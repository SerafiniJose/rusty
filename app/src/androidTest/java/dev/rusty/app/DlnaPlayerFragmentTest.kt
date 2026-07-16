package dev.rusty.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rusty.app.renderer.RendererIdentity
import dev.rusty.app.renderer.RendererMedia
import dev.rusty.app.renderer.RendererState
import dev.rusty.app.renderer.RendererStatus
import dev.rusty.app.renderer.RendererTransport
import dev.rusty.app.renderer.RendererUiRuntime
import dev.rusty.app.renderer.RendererUiSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 8 — the DLNA Player now-playing screen.
 *
 * Runs on a connected device via `connectedDebugAndroidTest`. The fragment's layout uses
 * MaterialButton/MaterialCardView, so the host must carry a MaterialComponents theme — we launch
 * under [R.style.Theme_Rusty] (the app theme), not the test harness's plain default. Each test
 * closes its scenario in [tearDown] so the fragment is actually driven through onStop/onDestroyView
 * (exercising the listener/ticker teardown), not just onStart.
 */
@RunWith(AndroidJUnit4::class)
class DlnaPlayerFragmentTest {

    private var scenario: FragmentScenario<DlnaPlayerFragment>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private class FakeRuntime(var snap: RendererUiSnapshot) : RendererUiRuntime {
        val commands = mutableListOf<String>()
        private val ls = mutableListOf<(RendererUiSnapshot) -> Unit>()
        fun emit(s: RendererUiSnapshot) {
            snap = s
            ls.forEach { it(s) }
        }
        override fun current() = snap
        override fun positionMs(): Long? = 0L
        override fun addListener(l: (RendererUiSnapshot) -> Unit) {
            ls.add(l)
            l(snap)
        }
        override fun removeListener(l: (RendererUiSnapshot) -> Unit) {
            ls.remove(l)
        }
        override fun play() { commands.add("play") }
        override fun pause() { commands.add("pause") }
        override fun stop() { commands.add("stop") }
        override fun seek(positionMs: Long) { commands.add("seek:$positionMs") }
    }

    private fun snap(
        status: RendererStatus,
        transport: RendererTransport? = null,
        meta: String = "",
    ) = RendererUiSnapshot(
        state = transport?.let {
            RendererState(
                transport = it,
                media = if (meta.isNotEmpty()) RendererMedia("http://u", meta, "audio/mpeg") else null,
            )
        },
        identity = RendererIdentity("Kitchen", "http://x/d.xml", status),
    )

    private fun launch(fake: FakeRuntime) {
        scenario = launchFragmentInContainer<DlnaPlayerFragment>(themeResId = R.style.Theme_Rusty).also { s ->
            s.onFragment {
                it.runtimeOverride = fake
                it.rebindForTest()
            }
        }
    }

    @Test
    fun stoppedStateShowsStartButton() {
        launch(FakeRuntime(snap(RendererStatus.STOPPED)))

        onView(withId(R.id.dlnaStatusTitle)).check(matches(withText("Renderer stopped")))
        onView(withId(R.id.dlnaStartButton)).check(matches(isDisplayed()))
    }

    @Test
    fun nowPlayingRendersParsedMetadata() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item><dc:title>T</dc:title><upnp:artist>A</upnp:artist></item></DIDL-Lite>"""
        launch(FakeRuntime(snap(RendererStatus.RUNNING, RendererTransport.PLAYING, didl)))

        onView(withId(R.id.dlnaTitle)).check(matches(withText("T")))
        onView(withId(R.id.dlnaArtist)).check(matches(withText("A")))
    }

    @Test
    fun nowPlayingShowsPlaceholderGlyphAndEyebrowWhenNoArt() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item><dc:title>T</dc:title><upnp:artist>A</upnp:artist></item></DIDL-Lite>"""
        launch(FakeRuntime(snap(RendererStatus.RUNNING, RendererTransport.PLAYING, didl)))

        onView(withId(R.id.dlnaArtGlyph)).check(matches(isDisplayed()))
        onView(withId(R.id.dlnaEyebrow)).check(matches(withText("NOW PLAYING")))
    }

    @Test
    fun pauseButtonIssuesCommand() {
        val fake = FakeRuntime(snap(RendererStatus.RUNNING, RendererTransport.PLAYING, "<x/>"))
        launch(fake)

        onView(withId(R.id.dlnaPlayPause)).perform(click())
        assertEquals(listOf("pause"), fake.commands)
    }
}
