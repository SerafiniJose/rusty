package dev.rusty.app

/**
 * Idle-timeout options for the screensaver. [prefSeconds] is what's persisted (0 = Never).
 * Pure (no Android deps) so the mapping + arm decision are unit-tested.
 */
enum class ScreensaverTimeout(val prefSeconds: Int, val label: String) {
    SEC_30(30, "30 seconds"),
    MIN_1(60, "1 minute"),
    MIN_2(120, "2 minutes"),
    MIN_5(300, "5 minutes"),
    NEVER(0, "Never");

    /** Delay before auto-entry, or null when auto-entry is disabled. */
    val timeoutMs: Long? get() = if (this == NEVER) null else prefSeconds * 1000L

    /** Whether the idle timer should be armed for this option. */
    val shouldArm: Boolean get() = timeoutMs != null

    companion object {
        val DEFAULT = MIN_2
        val ordered: List<ScreensaverTimeout> = listOf(SEC_30, MIN_1, MIN_2, MIN_5, NEVER)
        fun fromPrefSeconds(seconds: Int): ScreensaverTimeout =
            values().firstOrNull { it.prefSeconds == seconds } ?: DEFAULT
    }
}
