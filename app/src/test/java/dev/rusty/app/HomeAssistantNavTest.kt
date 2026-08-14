package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure in-app (SPA) dashboard-navigation logic: the [HomeAssistantUrl.pathWithQuery]
 * helper and the [HomeAssistantNav] decision + script builder.
 */
class HomeAssistantNavTest {

    // ---- HomeAssistantUrl.pathWithQuery -------------------------------------

    @Test fun pathWithQuery_root_yieldsSlash() {
        assertEquals("/", HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123"))
        assertEquals("/", HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123/"))
    }

    @Test fun pathWithQuery_dashboardPath() {
        assertEquals(
            "/b1039b78_baby-buddy-dashboard",
            HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123/b1039b78_baby-buddy-dashboard"),
        )
    }

    @Test fun pathWithQuery_preservesQueryAndFragment() {
        assertEquals(
            "/lovelace/0?edit=1#view",
            HomeAssistantUrl.pathWithQuery("http://ha.local:8123/lovelace/0?edit=1#view"),
        )
    }

    @Test fun pathWithQuery_invalidUrl_null() {
        assertEquals(null, HomeAssistantUrl.pathWithQuery(null))
        assertEquals(null, HomeAssistantUrl.pathWithQuery("   "))
    }

    // ---- HomeAssistantNav.shouldSpaNavigate ---------------------------------

    private val origin = "http://192.168.2.78:8123"

    @Test fun spa_whenReadyAndOnOrigin() {
        assertTrue(HomeAssistantNav.shouldSpaNavigate(true, "$origin/lovelace/0", origin))
    }

    @Test fun noSpa_whenFrontendNotReady() {
        // Cold load / just after a full reload: must hard-load, not pushState.
        assertFalse(HomeAssistantNav.shouldSpaNavigate(false, "$origin/lovelace/0", origin))
    }

    @Test fun noSpa_whenOffOrigin() {
        // Login redirect / foreign page: pushState can't route HA's frontend.
        assertFalse(HomeAssistantNav.shouldSpaNavigate(true, "https://login.example.com/auth", origin))
        assertFalse(HomeAssistantNav.shouldSpaNavigate(true, null, origin))
    }

    // ---- HomeAssistantNav.isAuthPath ----------------------------------------

    @Test fun isAuthPath_loginAndOnboarding() {
        assertTrue(HomeAssistantNav.isAuthPath("$origin/auth/authorize?response_type=code"))
        assertTrue(HomeAssistantNav.isAuthPath("$origin/onboarding.html"))
    }

    @Test fun isAuthPath_normalDashboardsAreNot() {
        assertFalse(HomeAssistantNav.isAuthPath("$origin/lovelace/0"))
        assertFalse(HomeAssistantNav.isAuthPath("$origin/b1039b78_baby-buddy-dashboard"))
        assertFalse(HomeAssistantNav.isAuthPath(origin))
    }

    // ---- HomeAssistantNav.navigateScript ------------------------------------

    @Test fun navigateScript_embedsPathAndDispatchesEvent() {
        val js = HomeAssistantNav.navigateScript("/b1039b78_baby-buddy-dashboard")
        assertTrue(js.contains("history.pushState(null,'','/b1039b78_baby-buddy-dashboard')"))
        assertTrue(js.contains("location-changed"))
    }

    @Test fun navigateScript_escapesSingleQuotes() {
        // A path can't normally contain a raw quote, but the builder must never break out of the string.
        val js = HomeAssistantNav.navigateScript("/a'b")
        assertTrue(js.contains("""/a\'b"""))
    }

    // ---- HomeAssistantNav.selectedThemeJs -----------------------------------

    @Test fun selectedThemeJsRemovesWhenDefaultAuto() {
        // No theme + auto mode = truly backend-selected → remove the override entirely.
        val remove = "try{localStorage.removeItem('selectedTheme');}catch(e){}"
        assertEquals(remove, HomeAssistantNav.selectedThemeJs(null, null))
        assertEquals(remove, HomeAssistantNav.selectedThemeJs("   ", HomeAssistantNav.MODE_AUTO))
    }

    @Test fun selectedThemeJsSetsThemeObjectForCustomName() {
        // HA reads selectedTheme as a {theme,dark} object (bare names still work via HA back-compat,
        // but the object form is what lets us also carry the light/dark flag).
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"Noctis\"}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs("Noctis", HomeAssistantNav.MODE_AUTO))
    }

    @Test fun selectedThemeJsDefaultThemeForcedDark() {
        // "Default" theme (no name) with Dark mode forces HA's built-in default theme dark.
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"default\",\"dark\":true}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs(null, HomeAssistantNav.MODE_DARK))
    }

    @Test fun selectedThemeJsDefaultThemeForcedLight() {
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"default\",\"dark\":false}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs(null, HomeAssistantNav.MODE_LIGHT))
    }

    @Test fun selectedThemeJsCustomThemeForcedDark() {
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"Noctis\",\"dark\":true}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs("Noctis", HomeAssistantNav.MODE_DARK))
    }

    @Test fun selectedThemeJsEscapesQuotesInName() {
        // A name containing a quote must stay valid JS (JSONObject.quote escapes it).
        val js = HomeAssistantNav.selectedThemeJs("My \"Dark\" Theme", HomeAssistantNav.MODE_AUTO)
        assertTrue(js.contains("{\"theme\":\"My \\\"Dark\\\" Theme\"}"))
    }

    // ---- parseCssColorToArgb ------------------------------------------------

    @Test fun parseCssColor_rgb() {
        // 0x0B0A0C — HA's near-black default background reported as rgb by the WebView.
        assertEquals(0xFF0B0A0C.toInt(), HomeAssistantNav.parseCssColorToArgb("rgb(11, 10, 12)"))
    }

    @Test fun parseCssColor_rgbNoSpaces() {
        assertEquals(0xFFFFFFFF.toInt(), HomeAssistantNav.parseCssColorToArgb("rgb(255,255,255)"))
    }

    @Test fun parseCssColor_rgbaDropsAlphaToOpaque() {
        // A translucent report must still yield a fully opaque strip (alpha forced to FF).
        assertEquals(0xFF102030.toInt(), HomeAssistantNav.parseCssColorToArgb("rgba(16, 32, 48, 0.5)"))
    }

    @Test fun parseCssColor_hexLong() {
        assertEquals(0xFF1C1C1E.toInt(), HomeAssistantNav.parseCssColorToArgb("#1c1c1e"))
    }

    @Test fun parseCssColor_hexShortExpands() {
        assertEquals(0xFFFFFFFF.toInt(), HomeAssistantNav.parseCssColorToArgb("#fff"))
        assertEquals(0xFF112233.toInt(), HomeAssistantNav.parseCssColorToArgb("#123"))
    }

    @Test fun parseCssColor_rejectsGarbage() {
        assertNull(HomeAssistantNav.parseCssColorToArgb(null))
        assertNull(HomeAssistantNav.parseCssColorToArgb(""))
        assertNull(HomeAssistantNav.parseCssColorToArgb("   "))
        assertNull(HomeAssistantNav.parseCssColorToArgb("transparent"))
        assertNull(HomeAssistantNav.parseCssColorToArgb("#12"))
        assertNull(HomeAssistantNav.parseCssColorToArgb("rgb(1, 2)"))
    }

    // ---- reportThemeColorsJs ------------------------------------------------

    @Test fun reportThemeColorsJs_reportsBothColorsAndIsSelfContained() {
        val js = HomeAssistantNav.reportThemeColorsJs()
        // Reports background + text through the discovery bridge, from HA's theme tokens.
        assertTrue(js.contains("RustyHaBridge.onBackgroundColor"))
        assertTrue(js.contains("RustyHaBridge.onTextColor"))
        assertTrue(js.contains("--primary-background-color"))
        assertTrue(js.contains("--primary-text-color"))
        // Never throws into the bridge.
        assertTrue(js.contains("catch"))
    }

    // ---- kioskJs ------------------------------------------------------------

    @Test fun kioskJs_neverHidesTheTopAppBarScaffoldItself() {
        // Regression: HA's newer panels (ha-panel-security / -light / -climate / -history …) use
        // <ha-top-app-bar-fixed> as the page SCAFFOLD — the dashboard content is slotted INSIDE it.
        // Hiding that host collapsed the whole panel to 0x0, so tapping e.g. Security from Overview
        // rendered a blank screen. Only the bar INSIDE its shadow root may be hidden.
        val js = HomeAssistantNav.kioskJs()
        assertFalse(js.contains("ha-top-app-bar-fixed{display:none"))
        assertFalse(js.contains("ha-top-app-bar-fixed,ha-top-app-bar{display:none"))
        assertFalse(js.contains("app-header,ha-top-app-bar-fixed{display:none"))
    }

    @Test fun kioskJs_stylesTheBarInsideTheScaffoldsShadowRoot() {
        // The scaffold host is never touched (it slots the page content); the bar is reached one
        // level deeper, in the scaffold's own shadow root, where it is restyled rather than hidden.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("ha-top-app-bar-fixed"))       // still looked up…
        assertTrue(js.contains("header.top-app-bar"))          // …to style its inner header
        assertTrue(js.contains("top-app-bar-fixed-adjust"))    // and un-pad the content wrapper
    }

    @Test fun kioskJs_paintsARustyBarInsteadOfHidingIt() {
        // The scaffold's inner header is restyled, not hidden: it is the only place a user on a
        // section panel can be told where they are and get back from.
        val js = HomeAssistantNav.kioskJs()
        assertFalse(js.contains("header.top-app-bar,header.mdc-top-app-bar{display:none"))
        assertTrue(js.contains("header.top-app-bar,header.mdc-top-app-bar{display:flex!important;"))
        assertTrue(js.contains("height:52px!important;"))
        // Same colour as the dashboard behind it — no seam, and it follows the HA theme.
        assertTrue(js.contains("background:var(--primary-background-color,#111)!important;"))
        assertTrue(js.contains("border-bottom:none!important;"))
        assertTrue(js.contains("box-shadow:none!important;"))
    }

    @Test fun kioskJs_titleUsesTheAppsCapsHeaderTreatment() {
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("text-transform:uppercase!important;"))
        assertTrue(js.contains("letter-spacing:.18em!important;"))
        assertTrue(js.contains("color:'+INK+'!important;"))
    }

    @Test fun kioskJs_theBarsInkFollowsTheThemeToo() {
        // The bar's BACKGROUND already tracks the HA theme; ink fixed at the app's near-white made
        // it unreadable on a light theme — near-white text and arrow on a near-white surface, with
        // a dark hairline ring around the control. The app's own colours stay as the fallbacks, so
        // a dark theme looks exactly as designed.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("var INK='var(--primary-text-color,#F3EEE7)';"))
        assertTrue(js.contains("var HAIRLINE='var(--divider-color,#2A2730)';"))
        // Nothing paints ink or the hairline directly any more.
        val code = js.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("color:#F3EEE7"))
        assertFalse(code.contains("fill:#F3EEE7"))
        assertFalse(code.contains("1px solid #2A2730"))
    }

    @Test fun kioskJs_hidesHasOwnNavigationControlsInTheBar() {
        // Both are HA's own fallback content for the bar's navigationIcon slot, which is exactly
        // where #rusty-home is inserted:
        //  - ha-menu-button opens the sidebar drawer, which the app disables — a dead control;
        //  - ha-icon-button-arrow-prev is HA's back arrow, rendered whenever a panel is entered with
        //    ?historyBack=1 — which is how HA's own Overview cards link to Security/Lights/Climate.
        //    Left visible it put a SECOND back arrow right beside ours.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("ha-menu-button,ha-icon-button-arrow-prev{display:none!important;}"))
    }

    @Test fun kioskJs_reservesTheBarsHeightOnlyWhereABarIsPainted() {
        // A panel that paints a bar reserves 52px so content sits below it; everything else stays
        // fully collapsed, or an ordinary dashboard would gain a 52px empty gap where its hidden
        // header was. The `.header` in the deep case is position:fixed, so this reservation — not
        // document flow — is what keeps the first rows out from under it.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("':host{--header-height:52px!important;}'"))
        assertTrue(js.contains("':host{--header-height:0px!important;}'"))
        assertTrue(js.contains("(bar||deep)?PANEL_BAR_CSS:PANEL_FLAT_CSS"))
        assertTrue(js.contains("deep?HUI_BAR_CSS:PANEL_FLAT_CSS"))
    }

    @Test fun kioskJs_restylesALovelaceToolbarThatHasSomewhereToGoBackTo() {
        // HA puts an ha-icon-button-arrow-prev in a Lovelace toolbar exactly when the current view
        // has a back destination — its home panel's area views, and any `subview`. Those are
        // reachable only by tapping a card and no chip can represent them, so hiding the toolbar
        // (as every other Lovelace page does) left NO way back at all. Read from the live DOM, so
        // Overview and the user's own dashboards report no arrow and are untouched.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("var deep=!!(hui&&hui.querySelector('.toolbar ha-icon-button-arrow-prev'));"))
        assertTrue(js.contains("if(deep) injectHome(hui,'.toolbar');"))
    }

    @Test fun kioskJs_theDeepBarKeepsHasChromeHidden() {
        // Unhiding the toolbar must not smuggle back the search / Assist / edit controls the kiosk
        // hides on every other page, nor HA's own back arrow that #rusty-home replaces.
        val js = HomeAssistantNav.kioskJs()
        val huiCss = js.substringAfter("var HUI_BAR_CSS=").substringBefore("HOME_BTN_CSS")
        assertTrue(huiCss.contains("ha-icon-button-arrow-prev,ha-menu-button,.action-items{display:none!important;}"))
        // …and it is the same control, styled the same way, as the scaffold bar's.
        assertTrue(js.contains("var HOME_BTN_CSS="))
        val barCss = js.substringAfter("var BAR_CSS=").substringBefore("var HUI_BAR_CSS")
        assertTrue(barCss.contains("HOME_BTN_CSS"))
    }

    @Test fun kioskJs_reappliesTheHeaderOnRouteChangesNotJustPanelSwaps() {
        // HA's home panel moves between its Overview and area views without swapping the panel, so
        // the MutationObserver never fires and only the route says the bar must appear or go.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("function retryHeader()"))
        assertTrue(js.contains("new MutationObserver(retryHeader)"))
        // Called from the path watcher, on a real change only.
        val report = js.substringAfter("function reportPath()").substringBefore("function retryReport()")
        assertTrue(report.contains("retryHeader();"))
    }

    @Test fun kioskJs_theRouteRetryRunsItsWholeWindow() {
        // Stopping at the first success would style the OUTGOING view: right after a route change
        // the view being replaced is often still mounted, and styling it would count as done.
        val js = HomeAssistantNav.kioskJs()
        val retry = js.substringAfter("function retryHeader()").substringBefore("function installObserver()")
        assertTrue(retry.contains("applyHeader();"))
        assertFalse(retry.contains("if(applyHeader()"))
        assertTrue(retry.contains("if(++k>50)"))
    }

    @Test fun kioskJs_reappliesStyleTextWhenItChanges() {
        // A panel can be styled before its scaffold mounts; the injector must be able to correct
        // an already-injected style rather than early-returning on id alone.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("if(s.textContent!==css) s.textContent=css;"))
    }

    @Test fun kioskJs_stillHidesLegacyHeaderSurfaces() {
        // Classic Lovelace (hui-root .header/.toolbar), the old app-layout header, and the
        // header-height token must keep being suppressed.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("--header-height:0px!important"))
        assertTrue(js.contains(".header{display:none!important;}"))
        assertTrue(js.contains(".toolbar{display:none!important;}"))
        assertTrue(js.contains("app-header{display:none!important;}"))
    }

    @Test fun kioskJs_hidesSidebarAndInstallsPanelSwapObserver() {
        // The rest of the kiosk contract is unchanged: no sidebar/drawer band, and the header kill is
        // re-applied when partial-panel-resolver swaps the active panel (SPA dashboard switch).
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("ha-sidebar{display:none!important;}"))
        assertTrue(js.contains("partial-panel-resolver"))
        assertTrue(js.contains("MutationObserver"))
    }

    @Test fun kioskJs_injectsABackControlIntoTheBar() {
        val js = HomeAssistantNav.kioskJs()
        // A real <button>, so Android TV's D-pad can reach it…
        assertTrue(js.contains("createElement('button')"))
        assertTrue(js.contains("rusty-home"))
        // …with a visible focus ring, since `all:unset` strips the default one.
        assertTrue(js.contains("#rusty-home:focus{outline:2px solid #1DB954!important;"))
        // Styled like the active dashboard chip: 10% accent fill, hairline border.
        assertTrue(js.contains("background:rgba(29,185,84,.10);"))
        assertTrue(js.contains("border:1px solid '+HAIRLINE+';"))
    }

    @Test fun kioskJs_backControlCallsTheBridge() {
        // Navigation is the app's decision, not the page's: the button reports the tap and the
        // fragment picks the destination.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("RustyHaBridge.onHomeTap()"))
    }

    @Test fun kioskJs_backControlIsInjectedOnlyOnce() {
        // applyHeader runs on a retry loop and on every panel swap; re-injecting would stack buttons.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("querySelector('#rusty-home')"))
    }

    @Test fun kioskJs_reportsLocationChangesToTheApp() {
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("RustyHaBridge.onPath("))
        // HA routes client-side, so catch every way the URL can change.
        assertTrue(js.contains("popstate"))
        assertTrue(js.contains("location-changed"))
        assertTrue(js.contains("pushState"))
        assertTrue(js.contains("replaceState"))
    }

    @Test fun kioskJs_reportsOnlyRealPathChanges() {
        // HA re-routes internally; without deduping, the chip bar would be rebuilt constantly.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("if(p===window.__rustyLastPath) return;"))
    }

    @Test fun kioskJs_reportsHasPanelKeyAlongsideThePath() {
        // The app resolves the path to a chip through HA's own panel key rather than parsing it.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("RustyHaBridge.onPath(p,panel)"))
    }

    @Test fun kioskJs_locationWatchIsInstalledOnce() {
        // Installed from a repeating retry loop; patching history twice would double every report.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("if(window.__rustyLocationWatch) return;"))
    }

    @Test fun kioskJs_reportedPathIncludesQueryAndHash() {
        // pathname alone loses OAuth's client_id/redirect_uri/state, History's ?entity_id=, and
        // Lovelace's ?edit=1 — all needed for a later restore to work.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("location.search"))
        assertTrue(js.contains("location.hash"))
    }

    @Test fun kioskJs_onlyRemembersThePathOnceThePanelIsKnown() {
        // Stamping __rustyLastPath unconditionally would permanently lock in a resolution made
        // while hass hasn't populated yet (panel still ''), since the dedup guard above would then
        // silently drop the correct re-report the next time this same path is (re)dispatched.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("if(panel) window.__rustyLastPath=p;"))
    }

    @Test fun kioskJs_reportsHassOwnPanelKeyAndNeverGuessesADefaultPanel() {
        // hass exposes no defaultPanel, and which panel the root lands on differs by HA version
        // ('lovelace' on older builds, 'home' on newer) — the app learns that from an actual landing
        // (decidePathReport). Guessing it here is what left the Overview chip dark on Overview.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("panel=(ha&&ha.hass)?(ha.hass.panelUrl||"))
        // Read past the explanatory comments — only the code may not name a default panel.
        // ('ha-panel-lovelace' is a real selector elsewhere in the script, hence the exact match.)
        val code = js.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("defaultPanel"))
        assertFalse(code.contains("'lovelace'"))
    }

    @Test fun kioskJs_panelFallsBackToThePathsOwnFirstSegment() {
        // panelUrl is what HA derives from the path anyway, so a frontend that ever drops the
        // property still reports something the app can resolve a chip from.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("p.replace(/[?#].*$/,'').split('/')[1]"))
    }

    @Test fun kioskJs_retriesTheReportWhenThePanelWasBlank() {
        // Nothing else re-fires reportPath: pushState/replaceState/popstate/location-changed only
        // fire on a REAL navigation, and applyAll's retry interval stops as soon as styling
        // succeeds. Without this bounded retry a blank first report is blank forever.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("if(!panel) retryReport();"))
        assertTrue(js.contains("function retryReport()"))
        // Single-flight + bounded, so it can never become a report storm.
        assertTrue(js.contains("if(window.__rustyPathRetry) return;"))
        assertTrue(js.contains("k>40"))
    }

    @Test fun kioskJs_headerIsNotDoneUntilTheBackControlExists() {
        // injectHome silently gives up while its container hasn't rendered. If the done-condition
        // only tracked the style tag, a pass landing in that window would clear the 20s retry and
        // leave a 52px bar with NO back control until the next panel swap — for either bar.
        val js = HomeAssistantNav.kioskJs()
        assertTrue(js.contains("return (a||b)&&(!bar||(c&&!!sr(bar).querySelector('#rusty-home')))&&"))
        assertTrue(js.contains("(!deep||!!hui.querySelector('#rusty-home'));"))
    }

    @Test fun kioskJs_scaffoldPanelsDoNotBlanketHideToolbar() {
        // Same over-broad shape that made this branch necessary: `.toolbar` inside a scaffold panel
        // is not guaranteed to be chrome (History renders its own filter row). The scaffold's real
        // bar is handled by BAR_CSS one level deeper, so the panel-level kill is not needed here.
        val js = HomeAssistantNav.kioskJs()
        // Each declaration is a '+'-joined string list terminated by "';".
        val barCss = js.substringAfter("var PANEL_BAR_CSS=").substringBefore("';")
        assertFalse(barCss.contains(".toolbar"))
        // The flat (non-scaffold) variant still needs it for classic Lovelace.
        val flatCss = js.substringAfter("var PANEL_FLAT_CSS=").substringBefore("';")
        assertTrue(flatCss.contains(".toolbar{display:none!important;}"))
    }

    // ---- isAuthCallback -----------------------------------------------------

    @Test fun isAuthCallback_matchesHasOwnRedirectBack() {
        // HA's authorize step redirects back to the ROOT with the grant in the query, so the path
        // itself ("/") looks perfectly ordinary — only the query gives it away.
        assertTrue(HomeAssistantNav.isAuthCallback("/?auth_callback=1&code=abc&state=xyz"))
        assertTrue(HomeAssistantNav.isAuthCallback("/lovelace/0?auth_callback=1"))
        // Some flows omit auth_callback; a code+state pair is the grant either way.
        assertTrue(HomeAssistantNav.isAuthCallback("/?code=abc&state=xyz"))
        assertTrue(HomeAssistantNav.isAuthCallback("http://ha.local:8123/?code=abc&state=xyz"))
    }

    @Test fun isAuthCallback_leavesOrdinaryDashboardQueriesAlone() {
        assertFalse(HomeAssistantNav.isAuthCallback("/security"))
        assertFalse(HomeAssistantNav.isAuthCallback("/history?entity_id=light.kitchen"))
        assertFalse(HomeAssistantNav.isAuthCallback("/lovelace/0?edit=1"))
        // `code` alone is not a grant (a dashboard could legitimately use it as a filter).
        assertFalse(HomeAssistantNav.isAuthCallback("/map?code=42"))
        assertFalse(HomeAssistantNav.isAuthCallback(null))
    }

    // ---- decidePathReport ---------------------------------------------------

    private val base = "http://ha.local:8123"
    private val dashboards = listOf(
        HomeAssistantDashboards.OVERVIEW,
        HomeAssistantDashboards.HaDashboard(title = "Security", urlPath = "security"),
        HomeAssistantDashboards.HaDashboard(title = "Mapa", urlPath = "map"),
    )

    private fun decide(
        path: String?,
        currentPanel: String? = "security",
        overviewPanel: String? = "home",
        expectingOverviewLanding: Boolean = false,
        baseUrl: String? = base,
        visible: String? = HomeAssistantDashboards.OVERVIEW_PATH,
    ) = HomeAssistantNav.decidePathReport(
        reportedPath = path,
        currentPanel = currentPanel,
        overviewPanel = overviewPanel,
        expectingOverviewLanding = expectingOverviewLanding,
        baseUrl = baseUrl,
        available = dashboards,
        visiblePath = visible,
    )

    @Test fun decidePath_normalDashboardPath() {
        val d = decide("/security")
        assertEquals("http://ha.local:8123/security", d.restoreUrl)
        assertEquals("security", d.activePath)
        assertTrue(d.refreshChips)
        // Nothing is learnt off an ordinary panel — only an Overview landing teaches.
        assertNull(d.overviewPanelToRemember)
    }

    @Test fun decidePath_noChipRefreshWhenAlreadyOnThatDashboard() {
        // The optimistic write in showDashboard already lit the chip; a confirming report must not
        // rebuild the row (and lose D-pad focus) for nothing.
        val d = decide("/security", visible = "security")
        assertEquals("http://ha.local:8123/security", d.restoreUrl)
        assertFalse(d.refreshChips)
    }

    @Test fun decidePath_blankPanelStillRemembersThePageButDefersTheChip() {
        // The URL half never needed the panel: a re-show must still restore the real page.
        val d = decide("/security", currentPanel = "")
        assertEquals("http://ha.local:8123/security", d.restoreUrl)
        assertFalse(d.refreshChips)
        assertNull(d.activePath)
        assertNull(d.overviewPanelToRemember)
    }

    @Test fun decidePath_landingOnTheOverviewPanelIsOverview() {
        // HA sent the root here, so this IS the app's Overview even though `home` is also a panel.
        val d = decide("/home/overview", currentPanel = "home", visible = "security")
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH, d.activePath)
        assertTrue(d.refreshChips)
    }

    @Test fun decidePath_learnsTheOverviewPanelFromTheLandingItWasToldToExpect() {
        // The regression this replaces: the page used to GUESS HA's default panel as 'lovelace'.
        // This HA sends its root to `home`, so the guess resolved Overview to "no chip" and the
        // Overview chip went dark while sitting on Overview. Nothing in `hass` names the default
        // panel, so the app learns it from where HA actually lands after it navigates to the root.
        val d = decide(
            "/home/overview",
            currentPanel = "home",
            overviewPanel = null,
            expectingOverviewLanding = true,
            visible = null,
        )
        assertEquals("home", d.overviewPanelToRemember)
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH, d.activePath)
        assertTrue(d.refreshChips)
    }

    @Test fun decidePath_theRootItselfIsNotTheLandingToLearnFrom() {
        // The SPA push to "/" reports before HA has re-routed, so `hass` still names the panel the
        // user is LEAVING. Learning there would pin Overview to that panel forever.
        val d = decide(
            "/",
            currentPanel = "security",
            overviewPanel = null,
            expectingOverviewLanding = true,
            visible = null,
        )
        assertNull(d.overviewPanelToRemember)
        // Still Overview: the root is Overview by definition, learnt panel or not.
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH, d.activePath)
    }

    @Test fun decidePath_overviewPanelNotYetLearntLeavesNoChipRatherThanTheWrongOne() {
        val d = decide("/home/overview", currentPanel = "home", overviewPanel = null, visible = null)
        assertEquals("http://ha.local:8123/home/overview", d.restoreUrl)
        assertNull(d.activePath)
        assertFalse(d.refreshChips)
    }

    @Test fun decidePath_authCallbackStoresNothing() {
        // A single-use grant must never become the URL a later onHiddenChanged re-loads, or the
        // user is bounced to the login screen instead of their dashboard.
        val d = decide("/?auth_callback=1&code=abc&state=xyz")
        assertNull(d.restoreUrl)
        assertFalse(d.refreshChips)
        assertNull(d.activePath)
    }

    @Test fun decidePath_authAndOnboardingPathsStoreNothing() {
        assertNull(decide("/auth/authorize?client_id=x").restoreUrl)
        assertNull(decide("/onboarding.html").restoreUrl)
    }

    @Test fun decidePath_hostilePathIsRejected() {
        // "@evil.example/" turns the host into userinfo when naively concatenated onto the origin.
        val d = decide("@evil.example/")
        assertNull(d.restoreUrl)
        assertFalse(d.refreshChips)
        val protocolRelative = decide("//evil.example/")
        assertNull(protocolRelative.restoreUrl)
    }

    @Test fun decidePath_unknownPanelClearsTheActiveChip() {
        val d = decide("/config/system", visible = "security")
        assertEquals("http://ha.local:8123/config/system", d.restoreUrl)
        assertNull(d.activePath)
        assertTrue(d.refreshChips)
    }

    @Test fun decidePath_noConfiguredBaseUrlStoresNothing() {
        val d = decide("/security", baseUrl = null)
        assertNull(d.restoreUrl)
        assertFalse(d.refreshChips)
    }
}
