package dev.rusty.app

import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle

/**
 * Drives feature-fragment transactions and owns the explicit [current] feature state.
 *
 * Wraps [FeatureNavState] (pure; JVM-tested) and [FragmentManager] (Android; instrumentation-
 * tested). The [onSwitched] callback fires after every successful transaction so the caller
 * (HomeActivity) can reconcile chrome, screensaver, and the clock without mixing fragment logic
 * into the Activity body.
 *
 * ## Retention (Task 18)
 *
 * Feature fragments are RETAINED, not destroyed-and-recreated. [switchTo] finds the target by tag
 * (the [FeatureId.name]) — adding it once if it has never been shown — then `hide`s the previously-
 * visible fragment and `show`s the target. The HA WebView therefore keeps its loaded dashboard and
 * login across switches instead of reloading every time.
 *
 * ### Hidden lifecycle is the linchpin
 *
 * `setMaxLifecycle` caps each retained fragment's lifecycle:
 *  - the SHOWN fragment is capped at `RESUMED` (fully live);
 *  - a HIDDEN fragment is capped at `CREATED` by default — BELOW `STARTED` — so its `onStop`
 *    runs. For Spotify that tears down the store listener, the 1 Hz playback tick, and the ambient
 *    mesh (all registered in `onStart`); when it is shown again `onStart` re-registers them. Because
 *    a hidden fragment is driven to `CREATED` (never re-`onStart`ed while hidden), there is no path
 *    that double-registers those listeners.
 *  - a feature MAY opt into staying `STARTED` while hidden via [Feature.retainStartedWhenHidden]
 *    (none do today). HA does NOT need it: its WebView pause/resume is driven purely by the
 *    fragment's own `onPause`/`onResume`, which the `RESUMED`↔`CREATED` cap drives for us (see below).
 *
 * ### HA WebView pause/resume — ONE mechanism
 *
 * [HomeAssistantFragment] already calls `webView.onResume()` in `onResume` and `webView.onPause()`
 * (+ cookie flush) in `onPause`. Capping a hidden HA fragment at `CREATED` runs its `onPause`
 * (WebView timers paused, cookies flushed); capping the shown one at `RESUMED` runs its `onResume`.
 * The pause/resume is therefore wired ENTIRELY through the lifecycle cap — we deliberately do NOT
 * add a duplicate `onHiddenChanged` hook, so the WebView is paused/resumed exactly once per switch.
 *
 * @param fm          The host activity's [FragmentManager].
 * @param containerId The view id that hosts the feature fragments (e.g. [R.id.featureContainer]).
 * @param state       The [FeatureNavState] that tracks the current id and the enabled ring.
 * @param onSwitched  Called on the main thread after each successful switch, with the new [FeatureId].
 */
class FeatureNavigator(
    private val fm: FragmentManager,
    private val containerId: Int,
    val state: FeatureNavState,
    private val onSwitched: (FeatureId) -> Unit,
) {
    /** Insets forwarded by the shell's window-insets listener; replayed into the shown fragment. */
    var latestInsets: WindowInsetsCompat? = null

    /** The currently-active [FeatureId] — authoritative; NOT derived from the fragment tag. */
    val current: FeatureId get() = state.current

    /**
     * The currently-VISIBLE feature fragment, resolved by the authoritative [current] tag.
     *
     * With retention multiple fragments share [containerId], so `findFragmentById(containerId)` is
     * ambiguous — callers that want "the live feature" must go through here (or [fragmentFor]).
     */
    val currentFragment: Fragment? get() = fragmentFor(current)

    /** The (possibly hidden, possibly absent) retained fragment for [id]. */
    fun fragmentFor(id: FeatureId): Fragment? = fm.findFragmentByTag(id.name)

    /**
     * Commits the initial fragment on a cold start (savedInstanceState == null). Uses [commitNow]
     * so the tag is readable immediately by chrome reconciliation that follows. The initial fragment
     * is `add`ed (not `replace`d) and capped at `RESUMED`, so subsequent [switchTo] calls can hide
     * it and bring it back without recreating it.
     *
     * @param startId The feature to display on launch (resolved from persistence by the caller).
     */
    fun commitInitial(startId: FeatureId) {
        val feature = FeatureRegistry.byId(startId)
        fm.commitNow {
            val fragment = feature.createFragment()
            add(containerId, fragment, startId.name)
            setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        }
        state.current = startId
        replayInsets()
        onSwitched(startId)
    }

    /**
     * Shows [id] via add/hide/show, RETAINING the previously-visible fragment.
     *
     * Find-or-add the target by tag, `hide` the outgoing fragment (capped at its hidden lifecycle),
     * `show` the target (capped at `RESUMED`), then replay insets and fire [onSwitched]. A switch to
     * the already-current feature is a no-op (no redundant transaction, no double onSwitched).
     */
    fun switchTo(id: FeatureId) {
        if (id == current && fragmentFor(id) != null) return
        val feature = FeatureRegistry.byId(id)
        val outgoing = currentFragment
        fm.commitNow {
            val target = fragmentFor(id) ?: feature.createFragment().also {
                add(containerId, it, id.name)
            }
            // Hide the outgoing fragment FIRST and drop it below STARTED, so its onStop runs (Spotify
            // tears down its listeners/tick/mesh) and we never have two visible fragments at once.
            if (outgoing != null && outgoing !== target) {
                hide(outgoing)
                setMaxLifecycle(outgoing, hiddenLifecycleFor(currentFeatureOrNull()))
            }
            show(target)
            setMaxLifecycle(target, Lifecycle.State.RESUMED)
        }
        state.current = id
        replayInsets()
        onSwitched(id)
    }

    /**
     * Removes (destroys) the retained fragment for [id], if present. Called when a feature is
     * disabled so its WebView/native resources are released rather than kept hidden indefinitely.
     * No-op if the fragment was never added or [id] is the currently-shown feature (the caller must
     * switch away first — disabling the active feature routes through [switchTo] then here).
     */
    fun removeRetained(id: FeatureId) {
        if (id == current) return
        val fragment = fragmentFor(id) ?: return
        fm.commitNow { remove(fragment) }
    }

    /**
     * Reconciles the retained fragments after a configuration-change restore.
     *
     * The [FragmentManager] re-adds every retained fragment on recreate, restoring each one's
     * hidden/shown flag — but the new Activity's [state] was just seeded from persistence, which is
     * authoritative. Force EXACTLY ONE fragment visible: show the one matching [current] (capped at
     * `RESUMED`) and hide every other retained feature fragment (capped at its hidden lifecycle).
     * Idempotent and safe to call from `onCreate` when `savedInstanceState != null`.
     */
    fun reconcileAfterRestore() {
        val currentTag = current.name
        val retained = fm.fragments.filter { it.id == containerId && it.tag != null }
        if (retained.isEmpty()) return
        fm.commitNow {
            for (fragment in retained) {
                if (fragment.tag == currentTag) {
                    show(fragment)
                    setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                } else {
                    hide(fragment)
                    val id = runCatching { FeatureId.valueOf(fragment.tag!!) }.getOrNull()
                    setMaxLifecycle(fragment, hiddenLifecycleFor(id))
                }
            }
        }
        replayInsets()
    }

    /**
     * Rebuilds the VIEW of the retained fragment for [id] in place (detach → attach), forcing its
     * `onCreateView`/`onViewCreated` to re-run so it picks up freshly-resolved, configuration-qualified
     * resources (e.g. Spotify's `layout-port`/`values-port`). No-op if the fragment was never added.
     *
     * Used on a configuration change the Activity now absorbs itself (`android:configChanges`) — only
     * the orientation-sensitive feature is rebuilt, so other features (notably HA's WebView) keep their
     * live state instead of reloading. [reconcileAfterRestore] re-asserts the shown/hidden + lifecycle
     * caps afterwards (the rebuilt fragment must end up exactly as visible as [current] dictates).
     */
    fun recreateFeatureView(id: FeatureId) {
        val fragment = fragmentFor(id) ?: return
        // detach + attach MUST be two separate transactions: in a single transaction the
        // FragmentManager collapses them (the view is never destroyed), so it would keep its old-
        // configuration view (e.g. Spotify's landscape layout left rendering in a portrait window).
        // Two commits force a real onDestroyView → onCreateView, re-inflating with the new config's
        // resources (layout-port / values-port).
        fm.commitNow { detach(fragment) }
        fm.commitNow { attach(fragment) }
        reconcileAfterRestore()
    }

    private fun currentFeatureOrNull(): FeatureId? = state.current

    private fun hiddenLifecycleFor(id: FeatureId?): Lifecycle.State {
        val opted = id?.let { FeatureRegistry.byId(it).retainStartedWhenHidden } ?: false
        return if (opted) Lifecycle.State.STARTED else Lifecycle.State.CREATED
    }

    private fun replayInsets() {
        latestInsets?.let { insets ->
            (currentFragment as? InsetAware)?.onInsets(insets)
        }
    }
}
