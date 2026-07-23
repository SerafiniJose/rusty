package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** The one decision about when the photo stage may swallow a touch. Kept free of view types. */
internal object SlideshowStage {
    /**
     * Whether the photo stage should consume a tap (to reveal the transport pill) instead of letting
     * it fall through to the screensaver's wake/exit path. It must consume ONLY when the pill can
     * actually appear: a stage that swallows a tap and then does nothing strands a touch user with no
     * way out of the saver whenever the clock — the theme's other exit affordance — is hidden.
     */
    fun consumesTaps(sleepLayer: Boolean, hasController: Boolean, hasStatus: Boolean): Boolean =
        !sleepLayer && hasController && !hasStatus

    /**
     * Whether the slideshow owns the remote: D-pad LEFT/RIGHT/CENTER drive photos and only
     * BACK/UP exit ([ShellKeyRouting]). True exactly when a real slideshow is running — a
     * controller is mounted and no status screen covers it. Deliberately BROADER than
     * [consumesTaps]: a sleep layer over another feature still owns the remote (photos are what's
     * on screen), while its touch contract — any tap returns to the feature — stays tap-only.
     */
    fun consumesNavKeys(hasController: Boolean, hasStatus: Boolean): Boolean =
        hasController && !hasStatus

    /**
     * Whether a slide may pair two portrait photos side by side. Pairing only makes sense in a
     * landscape viewport: the slide layout is a horizontal pair of weighted panes, so on a portrait
     * screen each pane is half-width and full-height and both photos shrink into mostly blur.
     * Before the first layout pass [width] and [height] are both 0, which correctly yields solo.
     */
    fun shouldPairPortraits(prefEnabled: Boolean, width: Int, height: Int): Boolean =
        prefEnabled && width > height
}

/**
 * The Slideshow screensaver theme: blurred-fill background, portrait
 * split view, crossfade + Ken Burns zoom, compact clock + photo-info overlays. All slideshow
 * decisions live in [SlideshowController]; this class only renders. The theme paints its own
 * dark base, so [rendersAmbientMesh] is false and error states appear on that base.
 *
 * Rendering model: two stacked layers alternate. The controller has already decoded the next
 * slide's photos before announcing it, so binding the back layer is a cache hit; the back layer is
 * then raised and crossfaded over the front one.
 */
class SlideshowTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var stage: FrameLayout
    private lateinit var layerA: Layer
    private lateinit var layerB: Layer
    private lateinit var clockGroup: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var chrome: View
    private lateinit var transport: View
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnExit: View
    private var host: ScreensaverHost? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideTransportRunnable = Runnable { hideTransport() }
    /**
     * Whether the transport overlay is logically up. Tracked separately from the view's visibility
     * because a fade-out leaves visibility VISIBLE until the end action runs — and a tap during
     * that window should mean "bring it back", not "hide the thing that is already going away".
     */
    private var transportShown = false
    /**
     * True while the saver is a pure ambient sleep layer over a non-receiver feature (the controller
     * signals that by hiding the chrome). A sleep layer has no chrome and must not sprout transport
     * controls either: every interaction just wakes back to the feature underneath.
     */
    private var sleepLayer = false
    private lateinit var launcher: FeatureLauncher
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var config: ImmichConfig? = null
    private var controller: SlideshowController? = null
    private var frontIsA = true
    /** Last slide handed to [renderSlide]; replayed if a pause disposed a bind before it landed. */
    private var lastSlide: Slide? = null
    /** Between [onShown] and [onHidden]; guards every callback path from touching a torn-down view. */
    private var showing = false
    /** The controller re-emits the same status on every retry — render only on change. */
    private var shownStatus: String? = null

    override val rendersAmbientMesh: Boolean get() = false

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        controller?.stop() // defensive: never leave a previous mount's loop running
        // Set before any render call below (e.g. the cfg==null status line): the view is being built
        // fresh here, never torn down, and the controller always calls onShown() right after this
        // returns — so treating the view as "showing" from this point on is safe and lets renderStatus
        // share the same guard as renderSlide without swallowing the very first status render.
        showing = true
        appContext = context.applicationContext
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_slideshow, parent, false)
        stage = root.findViewById(R.id.ifStage)
        val inflater = LayoutInflater.from(context)
        layerA = Layer.inflateInto(inflater, root.findViewById(R.id.ifLayerA))
        layerB = Layer.inflateInto(inflater, root.findViewById(R.id.ifLayerB))
        clockGroup = root.findViewById(R.id.ifClockGroup)
        clock = root.findViewById(R.id.ssClock)
        date = root.findViewById(R.id.ssDate)
        status = root.findViewById(R.id.ssStatus)
        chrome = root.findViewById(R.id.ssChrome)
        this.host = host
        transport = root.findViewById(R.id.ifTransport)
        btnPlayPause = root.findViewById(R.id.ifBtnPlayPause)
        btnExit = root.findViewById(R.id.ifBtnExit)
        // A clickable child consumes its own touch, so the controller's tap-anywhere wake path never
        // sees it — which is how a photo tap stops meaning "exit" for this theme. Only ever set from
        // syncStageClickable(), so the stage gives the touch back the moment the overlay cannot
        // appear. The transport container itself is not clickable: a tap between the big icons lands
        // here, which is what makes "tap empty space to dismiss" work.
        stage.setOnClickListener { if (transportShown) hideTransport() else revealTransport() }
        root.findViewById<ImageButton>(R.id.ifBtnPrev).setOnClickListener {
            controller?.previous()
            armAutoHide()
        }
        btnPlayPause.setOnClickListener { togglePaused() }
        root.findViewById<ImageButton>(R.id.ifBtnNext).setOnClickListener {
            controller?.next()
            armAutoHide()
        }
        // Both exits go through the host: the theme has no reference to the controller that owns
        // the overlay, and dismissToForeground() is the same path the launcher pills already use.
        clockGroup.setOnClickListener { this.host?.requestExit() }
        btnExit.setOnClickListener { this.host?.requestExit() }
        root.findViewById<ImageButton>(R.id.ssBtnSettings).setOnClickListener { host.openSettings() }
        root.findViewById<ImageButton>(R.id.ssBtnInfo).setOnClickListener { host.openInfo() }
        launcher = FeatureLauncher(
            toggle = root.findViewById(R.id.ssBtnLauncher),
            menu = root.findViewById<LinearLayout>(R.id.ssLauncherMenu),
            scrim = root.findViewById(R.id.ssLauncherScrim),
            activeTint = ContextCompat.getColor(context, R.color.accent_fallback),
            inactiveTint = ContextCompat.getColor(context, R.color.ink),
            itemLayoutRes = R.layout.view_launcher_item,
            minEntriesToShow = 2,
        ) { host.launcherEntries() }
        launcher.refresh()

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(context)
        val cfg = SlideshowSettings.config(prefs, secrets)
        config = cfg
        if (cfg == null) {
            renderStatus("Set up Slideshow in Settings")
        } else {
            val repo = ImmichRepository.shared
            controller = SlideshowController(
                fetchBatch = { size ->
                    withContext(Dispatchers.IO) {
                        repo.fetchSlideshowAssets(cfg, SlideshowSettings.filters(prefs), size)
                    }
                },
                prefetch = { asset -> prefetchAsset(asset) },
                scope = scope,
                intervalMs = { SlideshowSettings.intervalSeconds(prefs) * 1000L },
                // Gated on the viewport as well as the pref — see [SlideshowStage.shouldPairPortraits].
                // Read per slide, so a rotation is picked up on the next advance (HomeActivity absorbs
                // rotation via configChanges, so the view is never recreated).
                splitView = {
                    SlideshowStage.shouldPairPortraits(
                        SlideshowSettings.splitViewEnabled(prefs), stage.width, stage.height,
                    )
                },
                listener = object : SlideshowController.Listener {
                    override fun onSlide(slide: Slide) = renderSlide(slide)
                    override fun onStatus(s: SlideshowStatus) = renderStatus(
                        when (s) {
                            SlideshowStatus.Showing -> null
                            SlideshowStatus.Auth ->
                                "Immich rejected the API key — check Slideshow settings"
                            SlideshowStatus.Unreachable ->
                                "Immich server not reachable — check Slideshow settings"
                            SlideshowStatus.NoPhotos -> "No photos match your filters"
                        }
                    )
                },
            )
        }
        syncStageClickable()
        return root
    }

    /**
     * Re-evaluate whether the stage swallows taps. Must run at every point one of the predicate's
     * inputs moves: the controller appearing (or not) at mount, a status line arriving or clearing,
     * and the sleep-layer latch.
     */
    private fun syncStageClickable() {
        stage.isClickable = SlideshowStage.consumesTaps(
            sleepLayer = sleepLayer,
            hasController = controller != null,
            hasStatus = shownStatus != null,
        )
    }

    // ---- Image pipeline -----------------------------------------------------

    /**
     * The one request shape used by BOTH prefetch and bind. Keeping them byte-identical (same
     * loader, same application context, same absent size resolver, same header) is what makes the
     * "no blank panes" promise hold: the bind either hits the memory cache, or — if the bitmap was
     * evicted between decode and bind — hits the same disk-cache entry (keyed on the URL) instead of
     * the network. Auth travels in the header only; it is never in the URL, so it never reaches the
     * disk-cache key.
     */
    private fun photoRequest(context: Context, cfg: ImmichConfig, assetId: String): ImageRequest.Builder =
        ImageRequest.Builder(context)
            .data(ImmichRepository.shared.previewUrl(cfg, assetId))
            .setHeader(HEADER_API_KEY, cfg.apiKey)
            .allowHardware(false) // software bitmaps so the wash downscale can read pixels

    /** Decode into the dedicated cache. Runs on the controller's loop coroutine. */
    private suspend fun prefetchAsset(asset: ImmichAsset): Boolean {
        val context = appContext ?: return false
        val cfg = config ?: return false
        return try {
            ImmichImages.loader(context)
                .execute(photoRequest(context, cfg, asset.id).build()) is SuccessResult
        } catch (cancelled: CancellationException) {
            throw cancelled // stop() cancels through here; never report it as a decode failure
        } catch (e: Exception) {
            false
        }
    }

    // ---- Rendering ----------------------------------------------------------

    private fun renderSlide(slide: Slide) {
        if (!showing) return
        val back = if (frontIsA) layerB else layerA
        val front = if (frontIsA) layerA else layerB
        val showInfo = SlideshowSettings.showInfo(prefs)
        bindPane(back.pane1, slide.primary, showInfo)
        val secondary = slide.secondary
        if (secondary != null) {
            back.pane2.container.visibility = View.VISIBLE
            bindPane(back.pane2, secondary, showInfo)
        } else {
            back.pane2.container.visibility = View.GONE
            back.pane2.clear()
        }
        // Crossfade the back layer over the front one; Ken Burns runs on the incoming layer's pane
        // zoom containers (different views from the layers themselves, so the two
        // ViewPropertyAnimators — which are per-view and share a duration — cannot stomp on each
        // other). withLayer() keeps the alpha blend off the every-frame saveLayerAlpha path.
        back.cancelAnimations()
        front.cancelAnimations()
        back.layer.alpha = 0f
        back.resetZoom()
        back.layer.bringToFront() // within ifStage only; the chrome's order is untouched
        back.layer.animate().alpha(1f).setDuration(SlideshowController.CROSSFADE_MS)
            .withLayer().start()
        front.layer.animate().alpha(0f).setDuration(SlideshowController.CROSSFADE_MS)
            .withLayer().start()
        if (SlideshowSettings.zoomEnabled(prefs)) runKenBurns(back)
        frontIsA = !frontIsA
        lastSlide = slide
    }

    /**
     * [onHidden] disposes in-flight binds, so a pause landing inside a crossfade can leave the
     * now-visible layer holding a pane that never received its image. Re-issue those binds on the
     * way back in (a cache hit, no crossfade) instead of showing a blank pane until the next slide.
     */
    private fun rebindVisibleIfIncomplete() {
        val slide = lastSlide ?: return
        val front = if (frontIsA) layerA else layerB
        val showInfo = SlideshowSettings.showInfo(prefs)
        if (!front.pane1.bound) bindPane(front.pane1, slide.primary, showInfo)
        val secondary = slide.secondary
        if (secondary != null && !front.pane2.bound) bindPane(front.pane2, secondary, showInfo)
    }

    private fun bindPane(pane: Pane, asset: ImmichAsset, showInfo: Boolean) {
        val context = appContext ?: return
        val cfg = config ?: return
        val caption = ImmichApi.captionFor(asset, includePeople = true)?.takeIf { showInfo }
        pane.dispose()
        pane.bound = false
        val request = photoRequest(context, cfg, asset.id)
            .target(
                // Photo, wash and caption are applied together so a pane never labels one photo
                // with another's caption. On error the pane keeps its previous (coherent) content
                // rather than going blank — the prefetch already proved this asset decodes, so a
                // failure here means the caches were dropped underneath us.
                onSuccess = { drawable ->
                    pane.photo.setImageDrawable(drawable)
                    pane.wash.setImageBitmap(washBitmap(drawable))
                    pane.caption.text = caption.orEmpty()
                    pane.caption.visibility = if (caption.isNullOrBlank()) View.GONE else View.VISIBLE
                    pane.bound = true
                },
            )
            .build()
        pane.disposable = ImmichImages.loader(context).enqueue(request)
    }

    /**
     * Blurred fill = a heavy downscale shown centerCrop (the ArtworkProcessor idiom: a smooth
     * pseudo-blur on every API level, no RenderEffect). Null when the drawable has no readable
     * bitmap, which clears the wash rather than leaving the previous slide's colors behind.
     */
    private fun washBitmap(drawable: Drawable): Bitmap? {
        val source = (drawable as? BitmapDrawable)?.bitmap ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        val scale = WASH_MAX_PX.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(source, width, height, true)
        } catch (e: Exception) {
            null // e.g. a hardware/recycled bitmap: fall back to no wash
        }
    }

    /** Slow zoom over the slide's dwell time (crossfade + interval), matching upstream ImmichFrame:
     *  1.0 ↔ [KEN_BURNS_SCALE] with the direction (in or out) picked at random per pane, eased out
     *  so the motion is visible from the first seconds rather than crawling linearly. Applied to
     *  each pane's photo container, NOT the whole slide: the captions are siblings of those
     *  containers, so they stay pinned to their corners instead of scaling off-screen. */
    private fun runKenBurns(layer: Layer) {
        val duration = SlideshowController.CROSSFADE_MS +
            SlideshowSettings.intervalSeconds(prefs) * 1000L
        // Solo slides (the only kind on a portrait viewport, since the split-view gate landed) leave
        // pane2's container GONE; animating it would burn a ViewPropertyAnimator per slide on nothing.
        // cancelAnimations()/resetZoom() still cover BOTH panes, so this stays a subset of what is
        // cancelled and reset — a pane can never keep a scale it was left with.
        listOf(layer.pane1, layer.pane2).forEach { pane ->
            if (pane.container.visibility != View.VISIBLE) return@forEach
            val zoomIn = Random.nextBoolean()
            val start = if (zoomIn) 1f else KEN_BURNS_SCALE
            val end = if (zoomIn) KEN_BURNS_SCALE else 1f
            pane.zoom.scaleX = start
            pane.zoom.scaleY = start
            pane.zoom.animate()
                .scaleX(end)
                .scaleY(end)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(duration)
                .start()
        }
    }

    /** Null message hides the status line. Deduped: the controller repeats statuses on every retry. */
    private fun renderStatus(message: String?) {
        if (!showing) return
        if (message == shownStatus) return
        shownStatus = message
        if (message == null) {
            status.visibility = View.GONE
        } else {
            status.text = message
            status.visibility = View.VISIBLE
            // The status line occupies the same centered slot as the pill, and with nothing playing
            // there is nothing to transport: retire the pill rather than stack it on the message.
            hideTransport()
        }
        syncStageClickable()
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        val show = SlideshowSettings.showClock(prefs)
        clockGroup.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val now = System.currentTimeMillis()
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clock.text = ClockFormat.time(now, is24Hour, locale, zone)
        date.text = ClockFormat.date(now, locale, zone)
    }

    override fun onShown() {
        showing = true
        rebindVisibleIfIncomplete()
        controller?.start()
        // Pause survives an activity pause/resume within one mount; show why nothing is advancing.
        if (controller?.isPaused == true) revealTransport()
    }

    override fun onHidden() {
        showing = false
        hideHandler.removeCallbacks(hideTransportRunnable)
        resetTransportOverlay()
        controller?.stop()
        layerA.cancelAnimations()
        layerB.cancelAnimations()
        // Cancelling a Ken Burns leaves the pane parked at up to KEN_BURNS_SCALE, and
        // rebindVisibleIfIncomplete() re-binds on the way back in without touching the scale — so
        // without this the re-shown photo stays visibly over-zoomed until the next slide.
        layerA.resetZoom()
        layerB.resetZoom()
        // A cancelled crossfade leaves both layers part-way; settle them so a resume doesn't blend
        // two slides until the next one arrives.
        val front = if (frontIsA) layerA else layerB
        val back = if (frontIsA) layerB else layerA
        front.layer.alpha = 1f
        back.layer.alpha = 0f
        layerA.dispose()
        layerB.dispose()
    }

    override fun setChromeVisible(visible: Boolean) {
        if (!visible) launcher.collapse()
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
        sleepLayer = !visible
        // Stand the transport surface down while we are a sleep layer. Un-clicking the stage matters
        // as much as hiding the pill: a clickable stage swallows the touch, and the controller's
        // tap-anywhere wake path — the only way back to the feature underneath — would never fire.
        if (sleepLayer) {
            hideHandler.removeCallbacks(hideTransportRunnable)
            resetTransportOverlay()
        }
        syncStageClickable()
    }

    override fun refreshLauncher() = launcher.refresh()

    // ---- Transport overlay --------------------------------------------------

    /**
     * Fades the pill in and arms the auto-hide. Suppressed while a status line is up (unconfigured,
     * auth error, empty filters): there is nothing to transport. Idempotent — tapping the photo
     * again while the pill is up just re-arms the timer.
     *
     * [byKey] = revealed by a D-pad action rather than a tap: gates on [SlideshowStage.consumesNavKeys]
     * (a sleep layer still shows the pill for a key — the key just drove the photos) instead of the
     * tap predicate, which keeps a sleep layer's touch contract (any tap returns to the feature).
     */
    private fun revealTransport(byKey: Boolean = false) {
        if (!showing) return
        val allowed = if (byKey) {
            SlideshowStage.consumesNavKeys(controller != null, shownStatus != null)
        } else {
            SlideshowStage.consumesTaps(sleepLayer, controller != null, shownStatus != null)
        }
        if (!allowed) return
        transportShown = true
        syncPlayPauseIcon()
        showOverlayView(transport)
        // The ✕ substitutes for the clock as the exit affordance only when the clock is hidden.
        if (SlideshowSettings.showClock(prefs)) snapHidden(btnExit) else showOverlayView(btnExit)
        armAutoHide()
    }

    private fun showOverlayView(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(REVEAL_MS).start()
    }

    private fun hideTransport() {
        hideHandler.removeCallbacks(hideTransportRunnable)
        transportShown = false
        hideOverlayView(transport)
        hideOverlayView(btnExit)
    }

    private fun hideOverlayView(view: View) {
        if (view.visibility != View.VISIBLE) return // nothing on screen; don't run an empty animator
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(REVEAL_MS)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    /** Snaps one overlay view back to hidden without animating. */
    private fun snapHidden(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
    }

    /** Snaps the overlay back to hidden without animating — the view may be going away entirely. */
    private fun resetTransportOverlay() {
        transportShown = false
        snapHidden(transport)
        snapHidden(btnExit)
    }

    /** While paused the pill stays put: it is the only indication the slideshow is not running. */
    private fun armAutoHide() {
        hideHandler.removeCallbacks(hideTransportRunnable)
        if (controller?.isPaused == true) return
        hideHandler.postDelayed(hideTransportRunnable, AUTO_HIDE_MS)
    }

    private fun togglePaused() {
        val active = controller ?: return
        if (active.isPaused) active.resume() else active.pause()
        syncPlayPauseIcon()
        armAutoHide()
    }

    private fun syncPlayPauseIcon() {
        val paused = controller?.isPaused == true
        btnPlayPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        btnPlayPause.contentDescription = if (paused) "Resume slideshow" else "Pause slideshow"
    }

    override fun ownsRemote(): Boolean =
        showing && SlideshowStage.consumesNavKeys(
            hasController = controller != null,
            hasStatus = shownStatus != null,
        )

    /**
     * D-pad drives the photos whenever a slideshow is running — including as a sleep layer over
     * another feature (spec 2026-07-22). Each action reveals the transport pill so the result is
     * visible; CENTER/ENTER is the remote's only photo pause, since media-PLAY_PAUSE means music.
     */
    override fun onNavKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> controller?.previous()
            KeyEvent.KEYCODE_DPAD_RIGHT -> controller?.next()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> togglePaused()
            else -> return // shell-enforced; defensive
        }
        revealTransport(byKey = true)
    }

    // ---- View holders -------------------------------------------------------

    /** One pane of a slide: the photo, its blurred fill, its caption, and its in-flight request. */
    private class Pane(
        val container: View,
        val zoom: View,
        val photo: ImageView,
        val wash: ImageView,
        val caption: TextView,
    ) {
        var disposable: Disposable? = null
        /** True once a request has actually painted this pane (photo + wash + caption together). */
        var bound = false

        fun dispose() {
            disposable?.dispose()
            disposable = null
        }

        fun clear() {
            dispose()
            bound = false
            photo.setImageDrawable(null)
            wash.setImageDrawable(null)
            caption.visibility = View.GONE
        }
    }

    /** One of the two alternating slide layers: the crossfaded [layer] wrapping the zoomed panes. */
    private class Layer(val layer: FrameLayout, val pane1: Pane, val pane2: Pane) {
        fun cancelAnimations() {
            layer.animate().cancel()
            pane1.zoom.animate().cancel()
            pane2.zoom.animate().cancel()
        }

        fun resetZoom() {
            listOf(pane1.zoom, pane2.zoom).forEach {
                it.scaleX = 1f
                it.scaleY = 1f
            }
        }

        fun dispose() {
            pane1.dispose()
            pane2.dispose()
        }

        companion object {
            fun inflateInto(inflater: LayoutInflater, layer: FrameLayout): Layer {
                val content = inflater.inflate(R.layout.view_slide, layer, false)
                layer.addView(content)
                return Layer(
                    layer = layer,
                    pane1 = Pane(
                        container = content.findViewById(R.id.ifPane1),
                        zoom = content.findViewById(R.id.ifZoom1),
                        photo = content.findViewById(R.id.ifPhoto1),
                        wash = content.findViewById(R.id.ifWash1),
                        caption = content.findViewById(R.id.ifCaption1),
                    ),
                    pane2 = Pane(
                        container = content.findViewById(R.id.ifPane2),
                        zoom = content.findViewById(R.id.ifZoom2),
                        photo = content.findViewById(R.id.ifPhoto2),
                        wash = content.findViewById(R.id.ifWash2),
                        caption = content.findViewById(R.id.ifCaption2),
                    ),
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val HEADER_API_KEY = "x-api-key"
        /** Longest edge of the downscaled copy that becomes the blurred fill. */
        private const val WASH_MAX_PX = 48
        /** Upstream ImmichFrame's zoom endpoint (`scale(1) ↔ scale(1.3)` in its keyframes). */
        private const val KEN_BURNS_SCALE = 1.3f
        /** Fade for revealing/hiding the transport pill. */
        private const val REVEAL_MS = 150L
        /** Idle time before the pill fades out again; matches the shell's system-bar auto-hide. */
        private const val AUTO_HIDE_MS = 5_000L
    }
}
