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
