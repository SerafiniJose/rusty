package dev.rusty.app

/**
 * Pure decision + script helpers for in-app (SPA) dashboard navigation inside the Home Assistant
 * WebView. Android-free for unit testing.
 *
 * Home Assistant's frontend is a single-page app: once it is loaded and authenticated, switching
 * dashboards via its own client-side router (`history.pushState` + a `location-changed` event) keeps
 * the live WebSocket connection and the already-rendered Lovelace views warm in memory, so revisiting
 * a dashboard is near-instant — no full page reload. We fall back to a hard [WebView.loadUrl] when the
 * frontend isn't ready yet (cold load, login screen, or after an error), where pushState can't apply.
 */
object HomeAssistantNav {

    /**
     * True when a dashboard switch can be done in-app via pushState instead of a full reload:
     * the frontend has reported a successful discovery ([frontendReady]) AND the WebView is currently
     * sitting on the trusted HA origin (not the login page or a foreign redirect).
     */
    fun shouldSpaNavigate(frontendReady: Boolean, currentUrl: String?, origin: String?): Boolean =
        frontendReady && HomeAssistantUrl.isSameOrigin(currentUrl, origin)

    /**
     * True if [url]'s path is part of HA's auth / onboarding flow (the login screen), where the SPA
     * router isn't mounted and a finished load must NOT arm in-app navigation. Pure.
     */
    fun isAuthPath(url: String?): Boolean {
        val path = HomeAssistantUrl.pathWithQuery(url) ?: return false
        return path.startsWith("/auth/") || path.startsWith("/auth?") || path == "/auth" ||
            path.startsWith("/onboarding")
    }

    /**
     * True when [pathOrUrl]'s QUERY marks HA's OAuth return leg — the redirect back from
     * `/auth/authorize`, which lands on the ROOT (`/?auth_callback=1&code=…&state=…`). Its path is
     * indistinguishable from an ordinary page ([isAuthPath] passes it), but the `code` it carries is
     * single-use: re-loading that URL later spends nothing and drops the user on the login screen.
     * Matched on `auth_callback`, or a `code` + `state` pair (flows that omit `auth_callback`). Pure.
     */
    fun isAuthCallback(pathOrUrl: String?): Boolean {
        val query = pathOrUrl?.substringAfter('?', "")?.substringBefore('#').orEmpty()
        if (query.isEmpty()) return false
        val names = query.split('&')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return "auth_callback" in names || ("code" in names && "state" in names)
    }

    /**
     * What the app should do with a path Home Assistant's frontend reported for itself.
     *
     * @param restoreUrl            absolute URL to remember as "the page the user is on", so a later
     *                              re-show restores it. Null → remember nothing (unsafe/unrestorable).
     * @param activePath            the dashboard url_path to mark active, or null for "no chip".
     *                              Only meaningful when [refreshChips] is true.
     * @param refreshChips          true when [activePath] differs from what the chip row shows.
     * @param overviewPanelToRemember  the HA panel key the app's Overview lands on, newly learnt from
     *                              this report, or null when nothing was learnt. Also the signal to
     *                              stop expecting an Overview landing.
     */
    data class PathDecision(
        val restoreUrl: String? = null,
        val activePath: String? = null,
        val refreshChips: Boolean = false,
        val overviewPanelToRemember: String? = null,
    )

    /** True for HA's root path — `/`, or blank/query-only variants of it. Pure. */
    private fun isRootPath(path: String?): Boolean =
        path?.substringBefore('?')?.substringBefore('#')?.trim('/').isNullOrEmpty()

    /**
     * The whole decision behind a `RustyHaBridge.onPath` report, as one pure function so it can be
     * unit-tested (the glue in the fragment cannot be). Two INDEPENDENT halves:
     *
     *  - the URL half — remember where HA actually is, so re-showing the fragment restores that page
     *    rather than snapping back to the active chip. Needs only a safe same-origin path, never
     *    [currentPanel]; refused for HA's auth/onboarding flow and for its single-use OAuth callback.
     *  - the CHIP half — resolve the path to a dashboard. That needs to know which panel the app's
     *    synthetic Overview lands on, and HA does not tell us: it redirects its root to a default
     *    panel whose identity varies by version ('lovelace' on older builds, 'home' on newer) and
     *    exposes no `defaultPanel` on `hass`. Guessing it lights the wrong chip, so the app LEARNS it
     *    instead — [expectingOverviewLanding] marks the report that follows an app-initiated
     *    navigation to Overview, and whatever panel HA settles on there IS Overview's panel, returned
     *    as [PathDecision.overviewPanelToRemember] for the caller to keep.
     *
     * Until it has been learnt, chip resolution for that panel is DEFERRED rather than guessed,
     * leaving the row as it was; a blank [currentPanel] (hass not populated yet) defers too.
     */
    fun decidePathReport(
        reportedPath: String?,
        currentPanel: String?,
        overviewPanel: String?,
        expectingOverviewLanding: Boolean,
        baseUrl: String?,
        available: List<HomeAssistantDashboards.HaDashboard>,
        visiblePath: String?,
    ): PathDecision {
        // Validates that the path is a safe same-origin absolute path before it can ever become a URL
        // the app will hand to WebView.loadUrl — the page reports it, and addJavascriptInterface
        // exposes the bridge to every frame, so a hostile child frame could otherwise smuggle a
        // foreign origin past naive concatenation (e.g. "@evil.example/").
        val childUrl = HomeAssistantUrl.childUrlOrNull(baseUrl, reportedPath) ?: return PathDecision()
        // Neither the login flow nor the OAuth return leg is a page worth restoring to.
        if (isAuthPath(childUrl) || isAuthCallback(reportedPath)) return PathDecision()

        val panel = currentPanel?.trim()?.ifEmpty { null }
        // Defer the chip half only — the URL half is already decided.
        if (panel == null) return PathDecision(restoreUrl = childUrl)

        // The app navigates to Overview by loading the HA ROOT, so the first path HA settles on
        // afterwards is Overview's. The root itself is not that landing — HA is still redirecting —
        // so it marks the chip without teaching anything.
        if (expectingOverviewLanding) {
            val landed = !isRootPath(reportedPath)
            return PathDecision(
                restoreUrl = childUrl,
                activePath = HomeAssistantDashboards.OVERVIEW_PATH,
                refreshChips = HomeAssistantDashboards.OVERVIEW_PATH != visiblePath,
                overviewPanelToRemember = if (landed) panel else null,
            )
        }

        val active = HomeAssistantDashboards.activePathFor(reportedPath, overviewPanel, available)
        return PathDecision(
            restoreUrl = childUrl,
            activePath = active,
            refreshChips = active != visiblePath,
        )
    }

    /**
     * JS that navigates HA's frontend router to [path] without reloading: pushes the history entry
     * then dispatches the `location-changed` event the `<home-assistant>` root listens for. Wrapped in
     * try/catch so a frontend that ever drops the contract can't throw into the bridge.
     */
    fun navigateScript(path: String): String {
        val safe = path.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){try{" +
            "history.pushState(null,'','$safe');" +
            "window.dispatchEvent(new CustomEvent('location-changed',{detail:{replace:false}}));" +
            "}catch(e){}})();"
    }

    /** Light/dark modes for [selectedThemeJs]. Mirror HA's Auto/Light/Dark selector: auto omits the
     *  `dark` flag (HA follows system + theme support), light forces `dark:false`, dark forces `dark:true`. */
    const val MODE_AUTO = "auto"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    /**
     * localStorage write that applies (or clears) HA's frontend theme. Mirrors DOCK_HIDDEN_JS: run at
     * document-start so HA reads it during init and paints the chosen theme on first frame.
     *
     * HA stores the selection as a `{theme, dark}` object (`ThemeSettings`): `theme` is a theme name or
     * `"default"` for the built-in Home Assistant theme; `dark` is `true`/`false` to force dark/light or
     * omitted to follow the system. HA never lists the built-in default in `frontend/get_themes`, so a
     * null/blank [themeName] here means that default theme.
     *
     * When the user leaves the theme on Default AND mode on Auto there is nothing to override, so we
     * remove the key entirely — that is truly "backend-selected" (honours a server-configured default).
     */
    fun selectedThemeJs(themeName: String?, mode: String?): String {
        val name = themeName?.trim().orEmpty()
        val dark: Boolean? = when (mode?.trim()?.lowercase()) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> null
        }
        if (name.isEmpty() && dark == null) {
            return "try{localStorage.removeItem('selectedTheme');}catch(e){}"
        }
        val theme = if (name.isEmpty()) "default" else name
        val obj = buildString {
            append("{\"theme\":").append(org.json.JSONObject.quote(theme))
            if (dark != null) append(",\"dark\":").append(dark)
            append("}")
        }
        return "try{localStorage.setItem('selectedTheme',JSON.stringify($obj));}catch(e){}"
    }

    /**
     * JS that samples the Home Assistant frontend's *own* theme colours and reports them to the app
     * through the discovery bridge, so the native shell can match them:
     *  - the background ([RustyHaBridge.onBackgroundColor]) tints the reserved top/bottom strips
     *    (status-bar + floating-clock clearance above, chip-bar clearance below) — instead of the static
     *    near-black [R.color.bg_base], which reads as two black bands against a themed dashboard;
     *  - the text colour ([RustyHaBridge.onTextColor]) tints the shell chrome that floats over those
     *    strips (clock, settings, app-selector) so it stays legible on the themed background.
     *
     * It prefers HA's `--primary-background-color` / `--primary-text-color` CSS custom properties (the
     * tokens every HA theme paints the dashboard from), falling back to the computed `<body>` background
     * / colour. Each value is normalised to `rgb(r, g, b)` via a throwaway probe element so the native
     * parser only ever sees one shape. Runs on a short bounded poll (HA paints asynchronously and may
     * re-theme on first frames) and reports each only on change; wrapped in try/catch so a frontend that
     * drops the contract can't throw into the bridge.
     */
    fun reportThemeColorsJs(): String =
        "(function(){" +
            "function probe(v){if(!v)return '';try{" +
                "var p=document.createElement('div');p.style.color=v;p.style.display='none';" +
                "document.body.appendChild(p);var rgb=getComputedStyle(p).color;p.remove();" +
                "return rgb||'';}catch(e){return '';}}" +
            "function cssVar(n){try{return (getComputedStyle(document.documentElement)" +
                ".getPropertyValue(n)||'').trim();}catch(e){return '';}}" +
            "function bg(){var v=cssVar('--primary-background-color');" +
                "if(!v){try{var b=(getComputedStyle(document.body).backgroundColor||'').trim();" +
                    "if(b&&b!=='transparent'&&b!=='rgba(0, 0, 0, 0)')v=b;}catch(e){}}" +
                "return probe(v);}" +
            "function fg(){var v=cssVar('--primary-text-color');" +
                "if(!v){try{var c=(getComputedStyle(document.body).color||'').trim();if(c)v=c;}catch(e){}}" +
                "return probe(v);}" +
            "var lb='',lf='',n=0;" +
            "(function loop(){" +
                "var b=bg();if(b&&b!==lb){lb=b;try{RustyHaBridge.onBackgroundColor(b);}catch(e){}}" +
                "var f=fg();if(f&&f!==lf){lf=f;try{RustyHaBridge.onTextColor(f);}catch(e){}}" +
                "if(n++<20)setTimeout(loop,300);})();" +
        "})();"

    /**
     * Kiosk CSS injector: hides Home Assistant's own sidebar, drawer band and top bar by inserting
     * idempotent `<style>` tags into HA's nested shadow roots, so the WebView shows only dashboard
     * content. Injected on every finished page load; HA renders asynchronously, so the initial pass
     * runs on a bounded ~20s retry loop (a cold direct load boots the whole HA frontend, and an
     * ingress panel attaches its shadow/iframe seconds after partial-panel-resolver mounts).
     *
     * The sidebar/drawer styles live in stable roots (home-assistant-main, ha-drawer) and survive
     * in-app navigation. The header kill does NOT: it lives inside the active panel, which HA
     * RECREATES on every SPA dashboard switch — so a one-shot inject is lost the moment the user
     * changes dashboard. A MutationObserver on the stable partial-panel-resolver re-applies it
     * whenever the active panel swaps (no timing race against the swap).
     *
     * Where the top bar lives depends on the panel, and getting this wrong blanks the screen:
     *  - classic Lovelace nests it in `hui-root` as `.header`/`.toolbar` — safe to `display:none`;
     *  - `ha-panel-app` (custom/ingress webapp panels) exposes its own `div.header` wrapper directly
     *    in the panel's shadow — also safe;
     *  - HA's newer built-in panels (ha-panel-security, -light, -climate, -history …) wrap the page
     *    in `<ha-top-app-bar-fixed>`. That element is NOT a header: it is the page SCAFFOLD, and the
     *    dashboard content is slotted INSIDE it (`hui-view-container` sits in its default slot).
     *    Hiding that host collapses the entire panel to 0x0 — verified on-device, it is what made
     *    tapping e.g. Security from Overview render a blank screen. So for those panels we reach one
     *    level deeper and paint the `header` element inside the scaffold's OWN shadow root in Rusty
     *    style (52px, dashboard-matching background, caps-header title), reserving its height via
     *    `--header-height:52px` so content sits below rather than under the bar.
     *
     * Selectors target current HA — verify on-device (CDP) and tune here if a future HA renames them.
     */
    fun kioskJs(): String = """
        (function(){
          // dockedSidebar is already set at document-start by DOCK_HIDDEN_JS (onPageStarted), which
          // is the load-bearing write (it must run before HA reads the pref). No need to repeat it here.
          // Panels with no top-app-bar scaffold (Overview's ha-panel-home, classic ha-panel-lovelace,
          // ingress ha-panel-app): collapse the header away entirely, as before.
          var PANEL_FLAT_CSS=':host{--header-height:0px!important;}'+
            '.header{display:none!important;}'+
            '.toolbar{display:none!important;}'+
            'app-header{display:none!important;}';
          // Panels that DO use the scaffold get a real 52px bar, so reserve its height instead of
          // collapsing it — otherwise the bar would paint over the first rows of content. No
          // '.toolbar' kill here: this panel's own bar lives one level deeper (BAR_CSS, inside the
          // scaffold's shadow root), so a '.toolbar' out here is the panel's CONTENT — History
          // renders its filter row as one — and blanket-hiding it is exactly the mistake that made
          // these panels blank in the first place.
          var PANEL_BAR_CSS=':host{--header-height:52px!important;}'+
            'app-header{display:none!important;}';
          // The bar paints on HA's surface, so its INK has to come from HA's theme too. The app's own
          // ink (#F3EEE7) stays as the fallback and is what a dark theme resolves to anyway, but
          // hard-coding it made the bar unreadable on a light theme — near-white text and a
          // near-white arrow on a near-white background. The accent fill is translucent and reads on
          // both. Same reasoning for the hairline, which was a dark ring on a light surface.
          var INK='var(--primary-text-color,#F3EEE7)';
          var HAIRLINE='var(--divider-color,#2A2730)';
          // The app's own back control, shared by both bars so they stay one design.
          var HOME_BTN_CSS=
            '#rusty-home{all:unset;display:inline-flex;align-items:center;justify-content:center;'+
              'width:40px;height:40px;margin:0 4px 0 8px;border-radius:20px;cursor:pointer;'+
              'background:rgba(29,185,84,.10);border:1px solid '+HAIRLINE+';}'+
            '#rusty-home svg{width:22px;height:22px;fill:'+INK+';}'+
            '#rusty-home:focus{outline:2px solid #1DB954!important;outline-offset:2px;}';
          // Injected into <ha-top-app-bar-fixed>'s own shadow root — never onto the element itself,
          // which slots the page content. Repaints HA's bar in the app's own language: same colour
          // as the dashboard behind it (so it reads as one surface, not a strip), caps-header title,
          // and no divider or shadow.
          var BAR_CSS=
            'header.top-app-bar,header.mdc-top-app-bar{display:flex!important;'+
              'height:52px!important;min-height:52px!important;'+
              'background:var(--primary-background-color,#111)!important;'+
              'color:'+INK+'!important;border-bottom:none!important;'+
              'box-shadow:none!important;padding:0 4px!important;}'+
            'header .row,header div.row{height:52px!important;min-height:52px!important;'+
              'align-items:center!important;}'+
            'header span.title,.title{font-size:13px!important;letter-spacing:.18em!important;'+
              'text-transform:uppercase!important;color:'+INK+'!important;'+
              'font-weight:600!important;opacity:.92!important;}'+
            '.top-app-bar-fixed-adjust,.mdc-top-app-bar--fixed-adjust{padding-top:0!important;}'+
            // Both of these are HA's OWN fallback content for the bar's navigationIcon slot, so a
            // rule in the bar's shadow root reaches them (a panel that slots its own control from
            // its light DOM is untouched — that one is real up-navigation, not a duplicate).
            //  - ha-menu-button opens the sidebar drawer, which this app disables — a dead control;
            //  - ha-icon-button-arrow-prev is HA's back arrow, rendered whenever a panel is entered
            //    with ?historyBack=1 (which is how HA's own Overview cards link to Security, Lights,
            //    Climate…). It lands in the same slot as #rusty-home, so leaving it visible puts TWO
            //    back arrows side by side; ours wins because it is always present and Rusty-styled.
            'ha-menu-button,ha-icon-button-arrow-prev{display:none!important;}'+
            HOME_BTN_CSS;
          // A Lovelace panel renders its own toolbar inside hui-root instead of a scaffold, and HA
          // puts an ha-icon-button-arrow-prev in it exactly when the current view has somewhere to go
          // back TO — its home panel's area/section views (`/home/areas-salon`), and any dashboard
          // view marked `subview`. Those are reachable only by tapping a card, the chip row cannot
          // represent them, and with the toolbar hidden they had no way back at all. So on those, and
          // only those, the toolbar is restyled into the same bar rather than hidden — Overview and
          // the user's own dashboard views report no arrow and stay exactly as they were.
          var HUI_BAR_CSS=':host{--header-height:52px!important;}'+
            // Position:fixed, so it never pushes content — the reserved --header-height above is what
            // keeps the first rows out from under it.
            '.header{display:block!important;background:var(--primary-background-color,#111)!important;'+
              'border-bottom:none!important;box-shadow:none!important;}'+
            '.toolbar{display:flex!important;align-items:center!important;'+
              'height:52px!important;min-height:52px!important;padding:0 4px!important;'+
              'background:transparent!important;color:'+INK+'!important;}'+
            '.main-title{font-size:13px!important;letter-spacing:.18em!important;'+
              'text-transform:uppercase!important;color:'+INK+'!important;'+
              'font-weight:600!important;opacity:.92!important;margin:0 0 0 8px!important;}'+
            // HA's own back arrow is replaced by #rusty-home (see BAR_CSS), and .action-items is the
            // search / Assist / edit chrome the kiosk hides on every other page — unhiding the
            // toolbar must not smuggle it back in.
            'ha-icon-button-arrow-prev,ha-menu-button,.action-items{display:none!important;}'+
            HOME_BTN_CSS;
          function sr(el){return el&&el.shadowRoot;}
          function styled(root, id, css){
            if(!root) return false;
            var s=root.querySelector('#'+id);
            // Update in place: a panel can be styled before its scaffold mounts, so the correct CSS
            // for it is only known on a later pass.
            if(s){ if(s.textContent!==css) s.textContent=css; return true; }
            s=document.createElement('style');
            s.id=id; s.textContent=css;
            root.appendChild(s);
            return true;
          }
          function mainRoot(){
            var ha=document.querySelector('home-assistant');
            return sr(ha)&&sr(sr(ha).querySelector('home-assistant-main'));
          }
          function resolver(){ var m=mainRoot(); return m&&m.querySelector('partial-panel-resolver'); }
          function activePanel(){ var r=resolver(); return r&&r.firstElementChild; }
          function huiRoot(){
            var r=resolver(); if(!r) return null;
            var panel=r.firstElementChild;
            var hui=panel&&(panel.tagName.toLowerCase()==='hui-root'?panel
              :(sr(panel)&&sr(panel).querySelector('hui-root')));
            if(!hui){var lov=r.querySelector('ha-panel-lovelace');
              hui=lov&&sr(lov)&&sr(lov).querySelector('hui-root');}
            return sr(hui);
          }
          // The bar's only control, in both bars. HA either offers no back affordance at all (the
          // scaffold panels) or one that goes somewhere else (a Lovelace subview's arrow walks the
          // view stack), so the app adds its own; the destination is the app's decision, so the
          // button just reports the tap.
          function injectHome(barRoot, containerSel){
            if(!barRoot || barRoot.querySelector('#rusty-home')) return;
            var sect=barRoot.querySelector(containerSel);
            if(!sect) return;
            var b=document.createElement('button');
            b.id='rusty-home';
            b.setAttribute('aria-label','Back');
            b.innerHTML='<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 '+
              '1.41-1.41L7.83 13H20v-2z"/></svg>';
            b.addEventListener('click',function(){
              try{ if(window.RustyHaBridge) RustyHaBridge.onHomeTap(); }catch(e){}
            });
            sect.insertBefore(b, sect.firstChild);
          }
          function applyHeader(){
            var pr=sr(activePanel());
            var bar=pr&&pr.querySelector('ha-top-app-bar-fixed,ha-top-app-bar');
            var hui=huiRoot();
            // HA's own "there is somewhere to go back to" signal — see HUI_BAR_CSS. Read from the
            // live DOM rather than inferred from the path, so it needs no knowledge of which views
            // a dashboard has.
            var deep=!!(hui&&hui.querySelector('.toolbar ha-icon-button-arrow-prev'));
            // Reserve the bar's height only where a bar will actually be painted.
            var a=styled(pr,'rusty-kiosk-header',(bar||deep)?PANEL_BAR_CSS:PANEL_FLAT_CSS);
            var b=styled(hui,'rusty-kiosk-header',deep?HUI_BAR_CSS:PANEL_FLAT_CSS);
            var c=styled(sr(bar),'rusty-kiosk-bar',BAR_CSS);
            if(c) injectHome(sr(bar),'header section');
            if(deep) injectHome(hui,'.toolbar');
            // Not done until a bar present in this panel has been styled AND carries the back
            // control — otherwise the retry stops early and the panel keeps a 52px bar with no way
            // out (injectHome gives up silently while its container hasn't rendered yet), or
            // HA's unstyled bar stays over the content.
            return (a||b)&&(!bar||(c&&!!sr(bar).querySelector('#rusty-home')))&&
              (!deep||!!hui.querySelector('#rusty-home'));
          }
          function applyAll(){
            var m=mainRoot();
            var sidebar=styled(m,'rusty-kiosk-sidebar',
              'ha-sidebar{display:none!important;}'+
              'home-assistant-main,ha-drawer{--mdc-drawer-width:0px!important;}'+
              '.mdc-drawer-app-content{margin-left:0!important;margin-inline-start:0!important;}');
            var drawer=m&&m.querySelector('ha-drawer');
            var drawerDone=styled(sr(drawer),'rusty-kiosk-drawer',
              '.mdc-drawer-app-content{margin-left:0!important;margin-inline-start:0!important;}');
            // Re-apply the header kill to the CURRENT active panel every pass; a fresh panel from a
            // dashboard swap starts unstyled and gets restyled here / by the observer.
            var headerDone=applyHeader();
            installObserver();
            watchLocation();
            return sidebar&&drawerDone&&headerDone;
          }
          // Re-apply for a bounded ~5s window after anything that can change what the panel renders.
          // Two triggers, because a panel swap is not the only such change: HA's home panel moves
          // between its Overview and area views WITHOUT swapping the panel, and only the route says
          // so. Runs the whole window instead of stopping at the first success — right after a route
          // change the OUTGOING view is often still mounted, and styling that one would count as
          // success and never revisit the view that replaces it. applyHeader is idempotent, and
          // `styled` only writes when the CSS actually differs, so re-running is cheap. 5s because
          // ingress panels (ha-panel-app) attach their shadow/iframe well after insertion.
          function retryHeader(){
            if(window.__rustyHeaderRetry) clearInterval(window.__rustyHeaderRetry);
            var k=0;
            window.__rustyHeaderRetry=setInterval(function(){
              applyHeader();
              if(++k>50){ clearInterval(window.__rustyHeaderRetry); window.__rustyHeaderRetry=null; }
            },100);
          }
          // One observer per page lifetime: when partial-panel-resolver swaps its panel child
          // (dashboard switch), restyle the new panel once its shadow root mounts.
          function installObserver(){
            if(window.__rustyKioskObserver) return;
            var r=resolver(); if(!r) return;
            window.__rustyKioskObserver=new MutationObserver(retryHeader);
            window.__rustyKioskObserver.observe(r,{childList:true});
          }
          // HA routes client-side, so the app never learns where the user went. Report each real
          // path change so the chip bar can follow, and so returning to HA restores the page the
          // user was actually on.
          function reportPath(){
            try{
              // pathname alone loses OAuth's client_id/redirect_uri/state and dashboard query
              // params (History's ?entity_id=, Lovelace's ?edit=1) — carry all three.
              var p=(location.pathname||'/')+location.search+location.hash;
              if(p===window.__rustyLastPath) return;
              // A route change can move between VIEWS of one panel, which the panel observer never
              // sees (no child is swapped) — this is the only signal that the bar may need to appear
              // or disappear. Kicked off before the report so the repaint is already under way.
              retryHeader();
              // hass.panelUrl is HA's OWN resolution of the current route to a panel key, so the app
              // never has to parse or guess one. It is deliberately NOT the default panel: which
              // panel the root lands on differs by HA version (older builds default to 'lovelace',
              // newer ones to 'home') and hass.defaultPanel is absent on both, so the app LEARNS it
              // instead — see HomeAssistantNav.decidePathReport.
              var panel='';
              try{
                var ha=document.querySelector('home-assistant');
                // Falls back to the path's own first segment, which is what HA derives panelUrl from,
                // so a frontend that ever drops the property still reports something usable.
                panel=(ha&&ha.hass)?(ha.hass.panelUrl||p.replace(/[?#].*$/,'').split('/')[1]||''):'';
              }catch(e){}
              // Only remember this path once the panel is known: on a cold load hass may not have
              // populated yet (panel still ''), and stamping anyway would permanently suppress the
              // correct re-report the next time this same path is (re)dispatched.
              if(panel) window.__rustyLastPath=p;
              if(window.RustyHaBridge) RustyHaBridge.onPath(p,panel);
              // Nothing else would ever re-fire this: the patched history methods, popstate and
              // location-changed only fire on a REAL navigation, and applyAll's retry interval is
              // cleared the moment styling succeeds. Without this, a blank panel stays blank forever.
              if(!panel) retryReport();
            }catch(e){}
          }
          // Bounded, single-flight re-report for the "hass not populated yet" case: waits for hass
          // to appear (max ~10s) and reports once. __rustyLastPath was NOT stamped above, so the
          // dedupe lets the same path through; at most one timer exists, so no report storm.
          function retryReport(){
            if(window.__rustyPathRetry) return;
            var k=0;
            window.__rustyPathRetry=setInterval(function(){
              k++;
              var ha=document.querySelector('home-assistant');
              var ready=!!(ha&&ha.hass);
              if(ready||k>40){
                clearInterval(window.__rustyPathRetry);
                window.__rustyPathRetry=null;
                if(ready) reportPath();
              }
            },250);
          }
          function watchLocation(){
            if(window.__rustyLocationWatch) return;
            window.__rustyLocationWatch=true;
            ['pushState','replaceState'].forEach(function(m){
              var orig=history[m];
              history[m]=function(){ var r=orig.apply(this,arguments); reportPath(); return r; };
            });
            window.addEventListener('popstate',reportPath);
            window.addEventListener('location-changed',reportPath);
            reportPath();
          }
          // ~20s bounded retry: applyHeader/applyAll are idempotent, so re-running each tick is
          // cheap; the observer then maintains the header kill across later dashboard swaps.
          var n=0;
          var t=setInterval(function(){ n++; if(applyAll()||n>80) clearInterval(t); },250);
        })();
    """.trimIndent()

    /**
     * Parses a CSS colour string — as reported by [reportBackgroundColorJs] (normalised to
     * `rgb(r, g, b)` / `rgba(...)` by the WebView), and tolerant of `#rgb` / `#rrggbb` — into an OPAQUE
     * ARGB int for [android.view.View.setBackgroundColor]. Alpha is forced fully opaque so the reserved
     * shell strips never show through. Returns null when [css] is blank or can't be parsed. Pure (no
     * android.graphics.Color, so it unit-tests without Robolectric).
     */
    fun parseCssColorToArgb(css: String?): Int? {
        val s = css?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val rgb = when {
            s.startsWith("rgb") -> {
                val nums = Regex("\\d+").findAll(s).map { it.value.toInt() }.take(3).toList()
                if (nums.size < 3) null else Triple(nums[0], nums[1], nums[2])
            }
            s.startsWith("#") -> parseHexRgb(s.removePrefix("#"))
            else -> null
        } ?: return null
        val (r, g, b) = rgb
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun parseHexRgb(hex: String): Triple<Int, Int, Int>? = when (hex.length) {
        3 -> {
            val r = hex[0].digitToIntOrNull(16) ?: return null
            val g = hex[1].digitToIntOrNull(16) ?: return null
            val b = hex[2].digitToIntOrNull(16) ?: return null
            // #rgb shorthand doubles each nibble: f -> ff, 1 -> 11.
            Triple(r * 17, g * 17, b * 17)
        }
        6 -> {
            val r = hex.substring(0, 2).toIntOrNull(16) ?: return null
            val g = hex.substring(2, 4).toIntOrNull(16) ?: return null
            val b = hex.substring(4, 6).toIntOrNull(16) ?: return null
            Triple(r, g, b)
        }
        else -> null
    }
}
