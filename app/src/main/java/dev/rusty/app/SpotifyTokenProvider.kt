package dev.rusty.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Reads the currently-valid Bearer token, or null if none/expired. */
fun interface TokenSource {
    fun validToken(): String?
}

/**
 * Registers a one-shot "token refreshed" awaiter, triggers the native refresh AFTER registering
 * (so a fast broadcast can't be missed), then suspends until the signal or [timeoutMs]. Returns
 * true if signalled.
 */
fun interface TokenRefresh {
    suspend fun refreshAndAwait(timeoutMs: Long): Boolean
}

/**
 * Mirrors the Lyrics token flow: hand back a valid Bearer token, requesting a refresh from the
 * native core (via broadcast round-trip) when none is available or a forced rotation is needed.
 */
class SpotifyTokenProvider(
    private val source: TokenSource,
    private val refresh: TokenRefresh,
    private val timeoutMs: Long = 5_000L,
) {
    /**
     * @param forceRefresh true after a 401 — rotate even if [source] still reports a (rejected) token.
     * @return a usable Bearer token, or null if none arrived within the timeout.
     */
    suspend fun token(forceRefresh: Boolean): String? {
        if (!forceRefresh) source.validToken()?.let { return it }
        if (!refresh.refreshAndAwait(timeoutMs)) return null
        return source.validToken()
    }
}

/**
 * Production wiring shared by the now-playing fragment and the screensaver theme. The awaiter is
 * registered BEFORE [NativeBridge.requestAccessToken] fires, mirroring [LyricsActivity]'s ordering,
 * so the `ACTION_TOKEN` broadcast is never lost to a race.
 */
fun androidSpotifyTokenProvider(context: Context): SpotifyTokenProvider {
    val app = context.applicationContext
    return SpotifyTokenProvider(
        source = { TokenStore.accessToken?.takeIf { TokenStore.isValid() } },
        refresh = { timeoutMs ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) {
                            if (i?.action == ReceiverDashboardBroadcast.ACTION_TOKEN) {
                                runCatching { app.unregisterReceiver(this) }
                                if (cont.isActive) cont.resume(true) {}
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        app, receiver,
                        IntentFilter(ReceiverDashboardBroadcast.ACTION_TOKEN),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                    NativeBridge.requestAccessToken() // AFTER registering — no missed broadcast
                }
            } ?: false
        },
    )
}
