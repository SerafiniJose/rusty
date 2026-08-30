package dev.rusty.app

/**
 * Outcome of a `POST /api/announce/text`.
 *
 * There is deliberately no "audio was undecodable" case: the runtime hands the clip to the DLNA
 * pipeline without probing it, so a corrupt clip surfaces exactly the way a bad Home Assistant
 * announcement does — the renderer's transport reports ERROR_OCCURRED asynchronously. The
 * synchronous failures are the two the runtime CAN know at request time.
 */
sealed class ControlAnnounceResult {
    /** Accepted and handed to the DLNA pipeline; playback proceeds asynchronously. */
    object Ok : ControlAnnounceResult()

    /** The DLNA player service is not running, so there is no pipeline to play through. */
    object RendererUnavailable : ControlAnnounceResult()

    /** Text route only: the device's TTS engine failed to initialise or to synthesize. */
    object TtsUnavailable : ControlAnnounceResult()
}

/**
 * Pure validation shared by [ControlProtocol] (routing/tests, off-device): how large an
 * announcement may be.
 */
object ControlAnnounce {

    /**
     * Cap on `text` for the TTS route, matching the ballpark of Android's own
     * `TextToSpeech.getMaxSpeechInputLength()` (4000 on every mainstream engine). Enforced here in
     * the pure layer so an over-long request is a 400 the tests can pin, rather than an engine
     * error surfacing as a 503.
     */
    const val MAX_TEXT_CHARS = 4000
}
