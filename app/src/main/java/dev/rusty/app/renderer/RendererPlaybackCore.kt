package dev.rusty.app.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.rusty.app.NativeBridge
import dev.rusty.app.ReceiverStateStore
import dev.rusty.app.RustyApp

/**
 * Everything it takes to put a clip on the speaker without waking Spotify's owner up in anger:
 * the tested reducer ([RendererStore] over [reduceRenderer]), an ExoPlayer ([RendererPlayer]), the
 * Spotify session bridge ([RendererSpotifyBridge]) and the two timers the reducer schedules.
 *
 * Deliberately free of DLNA: no SSDP, no HTTP, no GENA, no UDN — none of which a clip needs to
 * make a sound. That separation is what lets announcements work with the media renderer switched
 * off entirely: [MediaRendererService] owns one of these and wraps it in the UPnP network layer,
 * while the control service builds a private one on demand (see [AnnouncementRouting]).
 *
 * Thread-safe in the same way the renderer already was: [RendererStore.dispatch] serializes from
 * any thread, and [RendererPlayer] trampolines every ExoPlayer call onto the main looper. Callers
 * may drive this from an HTTP pool thread.
 */
class RendererPlaybackCore(context: Context, private val prefsStore: RendererPrefsStore) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Exposed so a host can observe reduced state — [MediaRendererService] evented GENA from it.
     *  Dispatching into it directly is [dispatch]'s job; prefer [dispatchCommand] where a live
     *  Spotify snapshot is needed. */
    val store = RendererStore(::handleEffect)

    private val player = RendererPlayer(appContext, store)
    private val bridge = RendererSpotifyBridge(store)
    private val bridgeListener = ReceiverStateStore.Listener(bridge::onSnapshot)

    private val resumeRunnable = Runnable {
        val (playing, gen) = bridge.snapshot()
        store.dispatch(RendererEvent.ResumeTimerFired(playing, gen))
    }

    /** The pending fade-timer runnable; replaced by every ScheduleFadeTimer (single-slot, like
     *  the resume timer — the reducer's phases never need two fades pending at once).
     *  Confined to the store-drain thread, like every other mutable field here (RendererStore's
     *  single-drain serialization is what makes that safe without further synchronization). */
    private var fadeRunnable: Runnable? = null

    /** Set once by [release]; makes a late [playAnnouncement] answer honestly instead of feeding a
     *  torn-down player. */
    @Volatile private var released = false

    // Registration is the LAST thing the constructor does on purpose: ReceiverStateStore replays
    // the current snapshot to a new listener, which can dispatch SpotifyStartedPlaying straight
    // back into the store — every field that path touches has to exist by then.
    init {
        val receiver = RustyApp.from(appContext)
        // Primed SYNCHRONOUSLY before registering. addListener replays the current snapshot too,
        // but delivers it on the main thread, and a core built moments before an announcement
        // (which is exactly how the control service builds one) would otherwise arbitrate that
        // announcement against a default "Spotify is idle" bridge and play over the music at full
        // volume. Feeding the same snapshot twice is inert: the bridge bumps its generation only
        // on a session change, and a repeated playing=true is not a start edge.
        bridge.onSnapshot(receiver.snapshot)
        receiver.addListener(bridgeListener)
    }

    // -- driving it ------------------------------------------------------------------------

    val state: RendererState get() = store.state

    fun positionMs(): Long = player.positionMs()

    fun spotifySnapshot(): Pair<Boolean, Long> = bridge.snapshot()

    fun dispatch(event: RendererEvent) = store.dispatch(event)

    /**
     * The ONE construction site for turning a [RendererCommand] into a reducer event: a live
     * Spotify snapshot plus the mix/fade prefs, through [RendererCommandTranslator]. Both the
     * network SOAP Play handler and every local caller go through here, so a UI or announcement
     * command preserves Spotify arbitration and fade choreography rather than poking the player.
     */
    fun dispatchCommand(command: RendererCommand) {
        val (playing, gen) = bridge.snapshot()
        val event = RendererCommandTranslator.toEvent(
            command, playing, gen, RendererPrefs.mixMode(prefsStore), RendererPrefs.fadeMs(prefsStore),
        )
        store.dispatch(event)
    }

    /**
     * Plays a locally-stored clip through the SAME SetUri → Play chain a network control point
     * sends, so it inherits the whole announcement choreography for free: Spotify pause/duck
     * arbitration, fade timing, and — when this core belongs to the renderer service — GENA
     * eventing and the DLNA screen's now-playing UI.
     *
     * Returns false only when this core has already been released (racing its host's teardown).
     */
    fun playAnnouncement(uri: String, mime: String?, title: String): Boolean {
        if (released) return false
        store.dispatch(RendererEvent.SoapSetUri(uri, announcementDidl(title), mime))
        dispatchCommand(RendererCommand.Play)
        return true
    }

    /** Minimal DIDL-Lite for a local announcement, so the DLNA player screen and GENA
     *  subscribers see a real title instead of a bare file URI. */
    private fun announcementDidl(title: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"announcement\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${UpnpXml.escape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "</item></DIDL-Lite>"

    // -- Effect handling (RendererStore.EffectHandler) --------------------------------------

    private fun handleEffect(effect: RendererEffect) {
        when (effect) {
            is RendererEffect.PreparePlayer -> player.prepare(effect.uri, effect.mime, effect.generation)
            RendererEffect.PlayPlayer -> player.play()
            RendererEffect.PausePlayer -> player.pause()
            RendererEffect.StopPlayer -> player.stop()
            is RendererEffect.SeekPlayer -> player.seekTo(effect.positionMs)
            RendererEffect.PauseSpotify -> NativeBridge.pause()
            RendererEffect.ResumeSpotify -> NativeBridge.play()
            is RendererEffect.DuckSpotify ->
                NativeBridge.setSpotifyAttenuation(NativeBridge.DUCK_FACTOR, effect.fadeMs.toInt())
            is RendererEffect.MuteSpotify ->
                NativeBridge.setSpotifyAttenuation(0f, effect.fadeMs.toInt())
            is RendererEffect.RestoreSpotifyVolume ->
                NativeBridge.setSpotifyAttenuation(1f, effect.fadeMs.toInt())
            is RendererEffect.ScheduleFadeTimer -> {
                fadeRunnable?.let(mainHandler::removeCallbacks)
                val generation = effect.mediaGeneration   // stamped at SCHEDULE time, on purpose
                val r = Runnable {
                    val (_, sessionGen) = bridge.snapshot()
                    store.dispatch(RendererEvent.FadeTimerFired(generation, sessionGen))
                }
                fadeRunnable = r
                mainHandler.postDelayed(r, effect.delayMs)
            }
            RendererEffect.CancelFadeTimer -> {
                fadeRunnable?.let(mainHandler::removeCallbacks)
                fadeRunnable = null
            }
            is RendererEffect.ScheduleResumeTimer -> {
                // Re-arming (SoapSetUri while owning) must not stack two pending releases: the
                // earlier one would fire on the old, longer deadline and release Spotify mid-chain.
                mainHandler.removeCallbacks(resumeRunnable)
                mainHandler.postDelayed(resumeRunnable, effect.delayMs)
            }
            RendererEffect.CancelResumeTimer -> mainHandler.removeCallbacks(resumeRunnable)
        }
    }

    // -- teardown ---------------------------------------------------------------------------

    /**
     * Releases the player and hands Spotify back whatever this core owes it. Every step is
     * individually guarded: a core torn down mid-construction must never throw out of teardown,
     * and calling this twice must be a no-op.
     *
     * Shutdown is dispatched FIRST, while the store listeners a host attached are still there:
     * NativeBridge is process-static and store.dispatch is synchronous, so ResumeSpotify /
     * RestoreSpotifyVolume still fire correctly from here.
     */
    fun release() {
        released = true
        runCatching {
            val (playing, generation) = bridge.snapshot()
            store.dispatch(RendererEvent.Shutdown(playing, generation))
        }
        runCatching { RustyApp.from(appContext).removeListener(bridgeListener) }
        runCatching { player.release() }
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
    }
}
