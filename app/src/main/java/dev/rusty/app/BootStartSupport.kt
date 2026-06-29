package dev.rusty.app

/**
 * Android 15 (API 35) restricts launching a mediaPlayback foreground service from
 * BOOT_COMPLETED — startForegroundService throws ForegroundServiceStartNotAllowedException.
 * This object is pure-JVM so it can be tested without Android stubs; the SDK_INT lookup
 * happens at the call site, not here.
 */
object BootStartSupport {
    fun isReliable(sdkInt: Int): Boolean = sdkInt < 35
}
