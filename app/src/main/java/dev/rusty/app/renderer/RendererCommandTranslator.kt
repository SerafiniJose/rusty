package dev.rusty.app.renderer

/**
 * Pure mapping from a UI [RendererCommand] to the reducer [RendererEvent], given the live context
 * the service snapshots at dispatch time — identical to the network Play path in
 * [RendererHttpProtocol]'s `handleAvTransport`. Keeping this pure (no Android, no store) makes it
 * unit-testable off-device, and gives both callers (the SOAP handler and the UI-command backend
 * in [MediaRendererService]) exactly ONE construction site for [RendererEvent.SoapPlay].
 */
object RendererCommandTranslator {
    fun toEvent(
        command: RendererCommand,
        spotifyPlaying: Boolean,
        spotifySessionGen: Long,
        mixMode: SpotifyInterruption,
        fadeMs: Long,
    ): RendererEvent = when (command) {
        RendererCommand.Play ->
            RendererEvent.SoapPlay(spotifyPlaying, spotifySessionGen, mixMode, fadeMs)
        RendererCommand.Pause -> RendererEvent.SoapPause
        RendererCommand.Stop -> RendererEvent.SoapStop
        is RendererCommand.Seek -> RendererEvent.SoapSeek(command.positionMs)
    }
}
