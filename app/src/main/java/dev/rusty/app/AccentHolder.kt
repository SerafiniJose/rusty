package dev.rusty.app

/** Last artwork-derived accent, shared so the lyrics screen can use it as a background fallback. */
object AccentHolder {
    @Volatile
    var accent: Int = 0xFF1DB954.toInt()
}
