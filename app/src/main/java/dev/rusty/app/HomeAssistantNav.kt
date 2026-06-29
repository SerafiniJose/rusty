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
}
