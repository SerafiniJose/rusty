package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * In-app Spotify authorization screen.
 *
 * The desktop-style loopback redirect (`http://127.0.0.1:25566/callback`) is intercepted inside
 * [WebViewClient] before the WebView tries to load it, so the app never needs to open a listening
 * socket — important on a TV panel with no system browser. PKCE state/verifier are generated per
 * attempt; the code is exchanged and persisted only after the state matches.
 */
class SpotifyLoginDialog(
    private val activity: Activity,
    private val onLinked: (String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val verifier = SpotifyOAuth.newVerifier()
    private val state = SpotifyOAuth.newState()
    private var exchanging = false
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null

    fun show() {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_spotify_login, null)
        val d = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(root)
            setOnDismissListener {
                scope.cancel()
                webView?.destroy()
                webView = null
            }
        }
        dialog = d
        status = root.findViewById(R.id.tvSpotifyLoginStatus)
        progress = root.findViewById(R.id.spotifyLoginProgress)
        root.findViewById<View>(R.id.btnSpotifyLoginClose).setOnClickListener { d.dismiss() }
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                d.dismiss()
                true
            } else {
                false
            }
        }

        val web = root.findViewById<WebView>(R.id.spotifyLoginWebView)
        web.setBackgroundColor(ContextCompat.getColor(activity, R.color.bg_base))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        webView = web

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleRedirect(request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleRedirect(url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (handleRedirect(url)) view.stopLoading()
            }
        }

        d.show()
        web.loadUrl(SpotifyOAuth.authorizationUrl(state, verifier))
    }

    /** Returns true when [url] is our loopback callback (and therefore must not be loaded). */
    private fun handleRedirect(url: String): Boolean {
        if (!url.startsWith(SpotifyOAuth.REDIRECT_URI)) return false
        if (exchanging) return true
        when (val redirect = SpotifyOAuth.parseRedirect(url, state)) {
            SpotifyOAuth.Redirect.Ignored -> return false
            is SpotifyOAuth.Redirect.Failure -> {
                showMessage(redirect.message, error = true)
                return true
            }
            is SpotifyOAuth.Redirect.Code -> {
                exchanging = true
                progress?.visibility = View.VISIBLE
                showMessage("Linking Spotify…", error = false)
                scope.launch {
                    when (val result = SpotifyAccount.link(activity, redirect.code, verifier)) {
                        is SpotifyAccount.LinkResult.Linked -> {
                            dialog?.dismiss()
                            onLinked(result.displayName)
                        }
                        is SpotifyAccount.LinkResult.Failed -> {
                            exchanging = false
                            progress?.visibility = View.GONE
                            showMessage(result.message, error = true)
                        }
                    }
                }
                return true
            }
        }
    }

    private fun showMessage(text: String, error: Boolean) {
        status?.text = text
        status?.setTextColor(
            ContextCompat.getColor(activity, if (error) R.color.dot_red else R.color.muted_dim)
        )
    }
}
