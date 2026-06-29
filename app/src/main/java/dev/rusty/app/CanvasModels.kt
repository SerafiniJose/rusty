package dev.rusty.app

/** Result of resolving a Canvas for one track. */
sealed interface CanvasResult {
    /** No usable (non-empty, video-type) Canvas for this track. */
    object None : CanvasResult
    /** A playable Canvas loop URL. */
    data class Found(val url: String) : CanvasResult
}

/** Outcome of a single network fetch, before the controller decides on retry. */
sealed interface CanvasFetch {
    data class Success(val result: CanvasResult) : CanvasFetch
    /** HTTP 401 — the controller should refresh the token and retry once. */
    object Unauthorized : CanvasFetch
    /** Any other HTTP/decode/network failure — degrade to album art. */
    object Error : CanvasFetch
}

/** What both surfaces observe. */
sealed interface CanvasState {
    /** No Canvas (or toggle off / no track) — show album art. */
    object None : CanvasState
    /** A track is resolving; keep showing album art until resolved. */
    object Loading : CanvasState
    /** Play this looping Canvas URL over the album art. */
    data class Found(val url: String) : CanvasState
}
