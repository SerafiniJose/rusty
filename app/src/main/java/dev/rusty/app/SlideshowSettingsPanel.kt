package dev.rusty.app

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.request.Disposable
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab for the Slideshow screensaver feature: connection (URL + API key + capability
 * probes), filters (one summary row per category, each opening a searchable picker dialog over
 * lists fetched live from Immich), display options.
 *
 * Threading: every [ImmichRepository] call is blocking (network / disk), so all of them run inside
 * `withContext(Dispatchers.IO)`. The panel scope is `Dispatchers.Main.immediate` and is cancelled by
 * the cleanup lambda, which [SettingsSheet] invokes on BOTH tab-switch and dismiss — a cancelled
 * coroutine never resumes, so no background result can touch a view after the panel is gone.
 */
class SlideshowSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes = R.layout.settings_panel_slideshow

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(activity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repo = ImmichRepository.shared

        val urlInput = panel.findViewById<TextInputEditText>(R.id.etImmichUrl)
        val keyInput = panel.findViewById<TextInputEditText>(R.id.etImmichApiKey)
        val saveButton = panel.findViewById<MaterialButton>(R.id.btnImmichSave)
        val feedback = panel.findViewById<TextView>(R.id.tvImmichFeedback)
        val filtersHint = panel.findViewById<TextView>(R.id.tvImmichFiltersHint)
        val cleartextWarning = panel.findViewById<TextView>(R.id.tvImmichCleartextWarning)

        // ---- Collapsible sections ------------------------------------------------
        // Attention rule: an unconfigured server starts expanded so first-run setup is never
        // hidden behind a collapsed row; otherwise every visit starts fully collapsed.
        val configuredAtBind = SlideshowSettings.config(prefs, secrets) != null
        val serverSection = CollapsibleSection(
            panel.findViewById(R.id.headSsServer), panel.findViewById(R.id.bodySsServer),
            "Server", startExpanded = !configuredAtBind)
        val filtersSection = CollapsibleSection(
            panel.findViewById(R.id.headSsFilters), panel.findViewById(R.id.bodySsFilters),
            "Filters", startExpanded = false)
        val displaySection = CollapsibleSection(
            panel.findViewById(R.id.headSsDisplay), panel.findViewById(R.id.bodySsDisplay),
            "Display", startExpanded = false)

        fun renderSummaries() {
            val configured = SlideshowSettings.config(prefs, secrets) != null
            serverSection.setSummary(SlideshowSummaries.server(
                prefs.getString(SlideshowSettings.KEY_URL, null),
                SlideshowSettings.apiKey(secrets).isNotBlank(),
                SlideshowSettings.isVerified(prefs),
                SlideshowSettings.accountName(prefs)))
            val f = SlideshowSettings.filters(prefs)
            filtersSection.setSummary(SlideshowSummaries.filters(
                configured, f.albumIds.size, f.personIds.size, f.tagIds.size))
            displaySection.setSummary(SlideshowSummaries.display(
                SlideshowSettings.intervalSeconds(prefs),
                SlideshowSettings.showClock(prefs), SlideshowSettings.showInfo(prefs),
                SlideshowSettings.zoomEnabled(prefs), SlideshowSettings.splitViewEnabled(prefs)))
        }
        renderSummaries()

        // Keyed off the SAVED url, not the text field: it warns about what the app is actually
        // sending the key to, and re-evaluates on every save.
        fun renderCleartextWarning() {
            cleartextWarning.visibility =
                if (SlideshowSettings.isCleartext(prefs)) View.VISIBLE else View.GONE
        }
        renderCleartextWarning()

        urlInput.setText(prefs.getString(SlideshowSettings.KEY_URL, "").orEmpty())
        keyInput.setText(SlideshowSettings.apiKey(secrets))

        // Only ever one fetch in flight PER CATEGORY: a Save (or a rapid double-Save) while the
        // first fetch is still running would otherwise publish two sets of category states, the
        // slower/stale response last — leaving the rows showing the previous server's library.
        // Keyed by kind (not one job for all three) so retrying a single failed row cannot cancel
        // the two that are healthy.
        val filterJobs = mutableMapOf<ImmichFilterKind, Job>()

        // The Save-time identity fetch + capability probe. Held like the filter fetch jobs above
        // because together they are by far the longest request on this panel (four sequential
        // probes × 8s connect + 8s read ≈ a minute against a wrong host) — long enough for the
        // user to edit the URL and Save again underneath it. Without the handle its resumption
        // would paint the OLD server's verdict over the newer Save's feedback.
        var verifyJob: Job? = null

        // Set by the picker persist lambdas below, consumed once by the teardown lambda. Deliberately a
        // local of bind() and not a field: a second panel instance (tab switch, sheet reopen) gets its
        // own flag, so one visit's pending change can never be attributed to another's teardown.
        var filtersChanged = false

        // ---- Filters: per-category states + summary rows -------------------------------
        // Rows render from (ImmichCategoryState + persisted selection), never prefs alone:
        // prefs can't say whether the CURRENT server's lists are loading, failed, or valid.
        var albumsState: ImmichCategoryState = ImmichCategoryState.Unavailable
        var peopleState: ImmichCategoryState = ImmichCategoryState.Unavailable
        var tagsState: ImmichCategoryState = ImmichCategoryState.Unavailable
        var tearingDown = false
        var openDialog: ImmichFilterPickerDialog? = null

        // Every row-thumbnail request in flight. renderRows() replaces the thumb ImageViews
        // wholesale on each pass, so without this an old request would still be running against a
        // detached view — holding the Activity alive, and (after a connection change) still
        // carrying the OLD server's credentials. Disposing at the top of renderRows() and again at
        // teardown keeps the set bounded by what is actually on screen.
        val thumbRequests = mutableListOf<Disposable>()
        fun disposeThumbs() {
            thumbRequests.forEach { it.dispose() }
            thumbRequests.clear()
        }

        val rows = mapOf(
            ImmichFilterKind.ALBUMS to panel.findViewById<View>(R.id.rowImmichAlbums),
            ImmichFilterKind.PEOPLE to panel.findViewById<View>(R.id.rowImmichPeople),
            ImmichFilterKind.TAGS to panel.findViewById<View>(R.id.rowImmichTags),
        )

        fun stateFor(kind: ImmichFilterKind) = when (kind) {
            ImmichFilterKind.ALBUMS -> albumsState
            ImmichFilterKind.PEOPLE -> peopleState
            ImmichFilterKind.TAGS -> tagsState
        }

        fun selectedIdsFor(kind: ImmichFilterKind): List<String> {
            val f = SlideshowSettings.filters(prefs)
            return when (kind) {
                ImmichFilterKind.ALBUMS -> f.albumIds
                ImmichFilterKind.PEOPLE -> f.personIds
                ImmichFilterKind.TAGS -> f.tagIds
            }
        }

        fun persistFor(kind: ImmichFilterKind): (List<String>) -> Unit = { ids ->
            // One SlideshowSettings.setFilters transaction writing all three keys, instead of a
            // per-category setter each doing its own — so a reader (the slideshow fetch loop)
            // never observes a write straddling two of the three keys.
            val current = SlideshowSettings.filters(prefs)
            val updated = when (kind) {
                ImmichFilterKind.ALBUMS -> current.copy(albumIds = ids)
                ImmichFilterKind.PEOPLE -> current.copy(personIds = ids)
                ImmichFilterKind.TAGS -> current.copy(tagIds = ids)
            }
            SlideshowSettings.setFilters(prefs, updated)
            filtersChanged = true
            renderSummaries()
        }

        val accent = ContextCompat.getColor(activity, R.color.accent_fallback)
        val muted = ContextCompat.getColor(activity, R.color.muted_dim)

        /** Up to 3 overlapping 26dp thumbs of the SELECTED items (albums/people only). */
        fun renderThumbs(kind: ImmichFilterKind, row: View) {
            val holder = row.findViewById<LinearLayout>(R.id.layoutFilterRowThumbs)
            holder.removeAllViews()
            val cfg = SlideshowSettings.config(prefs, secrets) ?: return
            val state = stateFor(kind) as? ImmichCategoryState.Loaded ?: return
            val gen = SlideshowSettings.connectionGeneration(prefs)
            val byId = state.items.associateBy { it.id }
            val urls = selectedIdsFor(kind).mapNotNull { id ->
                when (kind) {
                    ImmichFilterKind.ALBUMS -> byId[id]?.thumbAssetId
                        ?.let { ImmichRepository.shared.previewUrl(cfg, it) }
                    ImmichFilterKind.PEOPLE -> byId[id]
                        ?.let { ImmichRepository.shared.personThumbUrl(cfg, id) }
                    ImmichFilterKind.TAGS -> null
                }
            }.take(3)
            val density = activity.resources.displayMetrics.density
            urls.forEachIndexed { i, url ->
                val iv = ImageView(activity)
                val size = (26 * density).toInt()
                val lp = LinearLayout.LayoutParams(size, size)
                if (i > 0) lp.marginStart = (-8 * density).toInt()
                iv.layoutParams = lp
                val key = ImmichPickerModel.thumbCacheKey(gen, url)
                thumbRequests += ImmichImages.loader(activity.applicationContext).enqueue(
                    ImageRequest.Builder(activity)
                        .data(url)
                        .setHeader(HEADER_API_KEY, cfg.apiKey)
                        .memoryCacheKey(key)
                        .diskCacheKey(key)
                        .transformations(
                            if (kind == ImmichFilterKind.PEOPLE) CircleCropTransformation()
                            else RoundedCornersTransformation(6 * density),
                        )
                        .target(iv)
                        .build(),
                )
                holder.addView(iv)
            }
        }

        fun renderRows() {
            if (tearingDown) return
            // The pass below re-creates every thumb view, so anything still in flight targets a
            // view that is about to be dropped.
            disposeThumbs()
            val configured = SlideshowSettings.config(prefs, secrets) != null
            val f = SlideshowSettings.filters(prefs)
            rows.forEach { (kind, row) ->
                val state = stateFor(kind)
                val selectedCount = selectedIdsFor(kind).size
                // Spec: rows hidden when unconfigured; visible otherwise (a Failed row with
                // selections must stay reachable so the filter can still be turned off).
                row.visibility = if (configured && state != ImmichCategoryState.Unavailable)
                    View.VISIBLE else View.GONE
                row.findViewById<TextView>(R.id.tvFilterRowTitle).text = kind.title
                val stateView = row.findViewById<TextView>(R.id.tvFilterRowState)
                stateView.text = ImmichPickerModel.stateLine(state, selectedCount, kind)
                stateView.setTextColor(
                    if (state is ImmichCategoryState.Loaded && selectedCount > 0) accent else muted,
                )
                row.isClickable = state !is ImmichCategoryState.Loading
                renderThumbs(kind, row)
            }
            if (configured) {
                filtersHint.text =
                    if (listOf(albumsState, peopleState, tagsState).all { it is ImmichCategoryState.Failed })
                        "Couldn't load the library — check the connection above."
                    else ImmichPickerModel.summaryLine(f.albumIds.size, f.personIds.size, f.tagIds.size)
            }
        }

        fun publish(kind: ImmichFilterKind, state: ImmichCategoryState) {
            // Generation guard: a fetch that raced a connection change publishes nothing.
            val gen = SlideshowSettings.connectionGeneration(prefs)
            val stateGen = when (state) {
                is ImmichCategoryState.Loading -> state.gen
                is ImmichCategoryState.Loaded -> state.gen
                is ImmichCategoryState.Failed -> state.gen
                ImmichCategoryState.Unavailable -> gen
            }
            if (stateGen != gen) return
            when (kind) {
                ImmichFilterKind.ALBUMS -> albumsState = state
                ImmichFilterKind.PEOPLE -> peopleState = state
                ImmichFilterKind.TAGS -> tagsState = state
            }
            renderRows()
        }

        /** One category's fetch. Own job, own Loading→Loaded/Failed cycle, own row. */
        fun loadCategory(kind: ImmichFilterKind, cfg: ImmichConfig, gen: Int) {
            publish(kind, ImmichCategoryState.Loading(gen))
            filterJobs.remove(kind)?.cancel()
            filterJobs[kind] = scope.launch {
                val r = withContext(Dispatchers.IO) {
                    when (kind) {
                        ImmichFilterKind.ALBUMS -> repo.fetchAlbums(cfg)
                        ImmichFilterKind.PEOPLE -> repo.fetchPeople(cfg)
                        ImmichFilterKind.TAGS -> repo.fetchTags(cfg)
                    }
                }
                publish(kind, when (r) {
                    is ImmichResult.Ok -> ImmichCategoryState.Loaded(gen, r.value)
                    is ImmichResult.Error -> ImmichCategoryState.Failed(gen)
                })
            }
        }

        /**
         * Loads [kinds] — all three on bind and on a connection change, exactly one on a row
         * retry. Scoping matters: a whole-panel reload publishes Loading over healthy Loaded
         * states, and Loaded is the ONLY place the fetched item lists live, so retrying one
         * failed row used to discard the other two categories' lists (rows go "Loading…",
         * lose their thumbs and stop being tappable — and settle on Failed if the server is
         * still down, which is the usual reason a retry is being pressed at all).
         */
        fun loadFilters(
            configChanged: Boolean = false,
            kinds: List<ImmichFilterKind> = ImmichFilterKind.entries,
        ) {
            if (configChanged) {
                // Nothing backed by the old server may stay tappable: drop the held lists
                // and close a picker mid-air BEFORE the new fetch. filtersChanged resets —
                // the wiped filters are subsumed by the connection reload (ImmichConnectionSwap).
                openDialog?.dismissSilently(); openDialog = null
                filtersChanged = false
            }
            val cfg = SlideshowSettings.config(prefs, secrets) ?: run {
                filterJobs.values.forEach { it.cancel() }
                filterJobs.clear()
                albumsState = ImmichCategoryState.Unavailable
                peopleState = ImmichCategoryState.Unavailable
                tagsState = ImmichCategoryState.Unavailable
                renderRows()
                filtersHint.text = "Save the connection first, then pick albums, people or tags."
                return
            }
            val gen = SlideshowSettings.connectionGeneration(prefs)
            // Concurrent (was sequential): albums are usable while a large People crawl still pages.
            kinds.forEach { loadCategory(it, cfg, gen) }
        }

        fun openPicker(kind: ImmichFilterKind) {
            val cfg = SlideshowSettings.config(prefs, secrets) ?: return
            val gen = SlideshowSettings.connectionGeneration(prefs)
            when (val state = stateFor(kind)) {
                is ImmichCategoryState.Loaded -> {
                    if (state.gen != gen) return // stale row: connection changed underneath
                    openDialog?.dismissSilently()
                    openDialog = ImmichFilterPickerDialog(
                        activity, kind, state.items, selectedIdsFor(kind).toSet(),
                        if (kind == ImmichFilterKind.TAGS) null else cfg, gen,
                        onSelectionChanged = persistFor(kind),
                        onDismissed = { openDialog = null; renderRows() },
                    ).also { it.show() }
                }
                is ImmichCategoryState.Failed -> {
                    if (selectedIdsFor(kind).isEmpty()) {
                        loadFilters(kinds = listOf(kind)) // retry THIS row only
                    } else {
                        // Selected-only picker: synthetic unknown rows so a filter whose
                        // fetch failed can STILL be cleared. cfg = null → no thumbnails.
                        openDialog?.dismissSilently()
                        openDialog = ImmichFilterPickerDialog(
                            activity, kind, emptyList(), selectedIdsFor(kind).toSet(),
                            null, gen,
                            onSelectionChanged = persistFor(kind),
                            onDismissed = { openDialog = null; renderRows() },
                        ).also { it.show() }
                    }
                }
                else -> Unit // Loading rows are not clickable; Unavailable rows are gone
            }
        }

        rows.forEach { (kind, row) -> row.setOnClickListener { openPicker(kind) } }

        saveButton.setOnClickListener {
            val result = SlideshowSettings.saveConnection(
                prefs, secrets, urlInput.text?.toString(), keyInput.text?.toString())
            if (result == ImmichConnectionSave.INVALID) {
                showFeedback(feedback, "Enter the Immich address and an API key.", HaFeedbackKind.NEUTRAL)
                return@setOnClickListener
            }
            // Echo back the normalized URL so the field always shows what was actually stored.
            urlInput.setText(prefs.getString(SlideshowSettings.KEY_URL, "").orEmpty())
            renderCleartextWarning()
            renderSummaries() // a changed connection cleared verified → header shows "Key not verified" until verify returns

            val configChanged = result == ImmichConnectionSave.SAVED_CONFIG_CHANGED
            if (configChanged) {
                loadFilters(configChanged = true)
                // Cache clear + saver reload live in the ACTIVITY scope (survives a tab switch).
                activity.onImmichConnectionChanged()
            }

            // Save is now the sign-in check: fetch identity + probe scopes off the main thread.
            val cfg = SlideshowSettings.config(prefs, secrets) ?: return@setOnClickListener
            saveButton.isEnabled = false
            showFeedback(feedback, "Saving…", HaFeedbackKind.NEUTRAL)
            verifyJob?.cancel()
            verifyJob = scope.launch {
                val user = withContext(Dispatchers.IO) { repo.fetchCurrentUser(cfg) }
                // Probe only when the host answered. A 401/403 still means it's up, so the four
                // sequential 8s-timeout probes stay fast and reveal whether the key works for the
                // slideshow even when it can't read the account name — a limited/scoped API key
                // 403s on /api/users/me yet authenticates photos fine. On an unreachable host the
                // probe is skipped so Save fails promptly instead of stalling ~64s on timeouts.
                val probes =
                    if (user is ImmichResult.Error && user.kind == ImmichErrorKind.UNREACHABLE) emptyList()
                    else withContext(Dispatchers.IO) { repo.testConnection(cfg, SlideshowSettings.filters(prefs)) }
                saveButton.isEnabled = true
                // Never echo the URL or the key into the status line; probe labels are static.
                fun withExtras(base: String, unavailable: List<String>): String = buildList {
                    add(base)
                    if (configChanged) add("Filters reset for the new server.")
                    if (unavailable.isNotEmpty()) add("Unavailable: ${unavailable.joinToString(", ")}.")
                }.joinToString(" ")
                // Every branch below also settles SlideshowSettings.setLastVerifyFailed: this `when` is
                // the only place an explicit verification verdict is reached, so a branch that left the
                // flag alone would strand the previous attempt's verdict on the current one — showing a
                // red "Connection issue" on the Info page for a key that just verified, or vice versa.
                when (val outcome = SlideshowSaveModel.of(user, probes)) {
                    is SlideshowSaveResult.SignedIn -> {
                        SlideshowSettings.setAccountName(prefs, outcome.name)
                        SlideshowSettings.setVerified(prefs, true)
                        SlideshowSettings.setLastVerifyFailed(prefs, false)
                        renderSummaries()
                        showFeedback(feedback,
                            withExtras("✓ Signed in as ${outcome.name}.", outcome.unavailable),
                            HaFeedbackKind.SUCCESS)
                    }
                    is SlideshowSaveResult.SavedNoIdentity -> {
                        // Key works for the slideshow; the name just isn't readable with this scoped
                        // key. Header becomes "{host} · key saved" (verified, renderSummaries reads no name).
                        SlideshowSettings.setVerified(prefs, true)
                        SlideshowSettings.setLastVerifyFailed(prefs, false)
                        renderSummaries()
                        showFeedback(feedback,
                            withExtras("✓ Saved. Account name unavailable — the API key lacks user access.",
                                outcome.unavailable),
                            HaFeedbackKind.SUCCESS)
                    }
                    // Verification failed: the key stays stored (so the field still shows what was
                    // typed and can be corrected), but it is NOT verified — re-render so the header
                    // drops the optimistic "key saved" and reads "Key not verified" instead of lying.
                    SlideshowSaveResult.InvalidKey -> {
                        SlideshowSettings.setLastVerifyFailed(prefs, true)
                        renderSummaries()
                        showFeedback(feedback, "✗ Couldn't sign in — check the API key.", HaFeedbackKind.ERROR)
                    }
                    SlideshowSaveResult.Unreachable -> {
                        SlideshowSettings.setLastVerifyFailed(prefs, true)
                        renderSummaries()
                        showFeedback(feedback, "✗ Couldn't reach the server.", HaFeedbackKind.ERROR)
                    }
                }
            }
        }

        // ---- Display ----
        // The slider index is the source of truth for BOTH thumb and label, so a stored interval
        // that is not one of INTERVAL_STEPS (older build / hand-edited pref) can't show a label
        // that disagrees with where the thumb actually sits. intervalIndexFor() owns that mapping
        // (pure, unit-tested) so the off-step case is not stranded inside this Android-bound class.
        val slider = panel.findViewById<Slider>(R.id.sliderImmichInterval)
        val intervalValue = panel.findViewById<TextView>(R.id.tvImmichIntervalValue)
        val steps = SlideshowSettings.INTERVAL_STEPS
        val startIndex = SlideshowSettings.intervalIndexFor(SlideshowSettings.intervalSeconds(prefs))
        slider.value = startIndex.toFloat()
        intervalValue.text = SlideshowSettings.intervalLabel(steps[startIndex])
        slider.addOnChangeListener { _, value, _ ->
            intervalValue.text = SlideshowSettings.intervalLabel(steps[value.toInt()])
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                // No onSlideshowConfigChanged() here: SlideshowTheme reads the interval through
                // a live lambda on every advance, so a mounted saver picks the new value up on its
                // next slide. Remounting would abort the running slideshow for nothing. (Filters are
                // different: they are only re-read on a batch refill, so they are debounced to one
                // remount at teardown — see the picker persist lambdas above.)
                SlideshowSettings.setIntervalSeconds(prefs, steps[slider.value.toInt()])
                renderSummaries()
            }
        })
        // The four display switches are likewise read per-slide by the theme, so they take effect
        // on the next photo without a remount.
        bindSwitch(panel, R.id.switchImmichClock, SlideshowSettings.showClock(prefs)) {
            SlideshowSettings.setShowClock(prefs, it); renderSummaries()
        }
        bindSwitch(panel, R.id.switchImmichInfo, SlideshowSettings.showInfo(prefs)) {
            SlideshowSettings.setShowInfo(prefs, it); renderSummaries()
        }
        bindSwitch(panel, R.id.switchImmichZoom, SlideshowSettings.zoomEnabled(prefs)) {
            SlideshowSettings.setZoomEnabled(prefs, it); renderSummaries()
        }
        bindSwitch(panel, R.id.switchImmichSplit, SlideshowSettings.splitViewEnabled(prefs)) {
            SlideshowSettings.setSplitViewEnabled(prefs, it); renderSummaries()
        }

        loadFilters()
        return {
            // Ordering is load-bearing. tearingDown gates every render path first, so nothing that
            // is still on its way in can paint into views that are going away. Cancellation then
            // happens unconditionally and before anything else — no in-flight fetch may resume,
            // flag or no flag — and a picker still on screen is dismissed WITHOUT its onDismissed
            // re-render. Only then is the debounced filter change applied: at most one remount,
            // and only if a selection was actually toggled this visit.
            tearingDown = true
            scope.cancel()
            disposeThumbs()
            openDialog?.dismissSilently()
            openDialog = null
            if (filtersChanged) activity.onSlideshowConfigChanged()
        }
    }

    private fun bindSwitch(panel: View, id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = panel.findViewById<SwitchMaterial>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"

        /** Auth travels in this header only — never in a URL, and therefore never in a cache key. */
        private const val HEADER_API_KEY = "x-api-key"
    }
}
