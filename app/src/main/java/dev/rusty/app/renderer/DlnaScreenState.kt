package dev.rusty.app.renderer

/** The distinct screens the DLNA now-playing fragment can show. */
enum class DlnaScreen { STOPPED, STARTING, FAILED, READY, BUFFERING, NOW_PLAYING, PLAYBACK_ERROR }

/**
 * Pure mapping from a runtime snapshot to a screen state. Status is the outer switch; when RUNNING,
 * the transport (and an ERROR_OCCURRED override) decides. Media-present-but-stopped is READY, not
 * NOW_PLAYING — a renderer keeps media across a Stop.
 */
fun dlnaScreenFor(snapshot: RendererUiSnapshot): DlnaScreen {
    return when (snapshot.identity.status) {
        RendererStatus.STOPPED -> DlnaScreen.STOPPED
        RendererStatus.STARTING -> DlnaScreen.STARTING
        RendererStatus.FAILED -> DlnaScreen.FAILED
        RendererStatus.RUNNING -> {
            val state = snapshot.state ?: return DlnaScreen.READY
            if (state.transportStatus == "ERROR_OCCURRED") return DlnaScreen.PLAYBACK_ERROR
            when (state.transport) {
                RendererTransport.NO_MEDIA_PRESENT, RendererTransport.STOPPED -> DlnaScreen.READY
                RendererTransport.TRANSITIONING -> DlnaScreen.BUFFERING
                RendererTransport.PLAYING, RendererTransport.PAUSED_PLAYBACK -> DlnaScreen.NOW_PLAYING
            }
        }
    }
}
