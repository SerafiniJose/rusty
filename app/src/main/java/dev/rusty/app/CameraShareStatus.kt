package dev.rusty.app

/**
 * In-memory, process-static publisher of what this device's camera-share RTSP server is ACTUALLY
 * doing. The listener machinery — replaying registration, main-thread delivery, and why none of
 * this is persisted — lives in [StatusPublisher]; this adds only the states.
 */
object CameraShareStatus : StatusPublisher<CameraShareStatus.State>(State.Off) {

    sealed class State {
        object Off : State()
        object Ready : State()
        /** [width]/[height]/[fps]/[bps] are MEASURED (see StreamMeter) and 0 until the first
         *  window closes; a bare viewer count is the state before any frame left the encoder. */
        data class Streaming(
            val viewers: Int,
            val width: Int = 0,
            val height: Int = 0,
            val fps: Int = 0,
            val bps: Int = 0,
        ) : State()
        data class Unavailable(val reason: String) : State()
        data class Unsupported(val reason: String) : State()
    }
}
