package dev.rusty.app.renderer

/**
 * Chooses the HTTP port. Pure — the actual bind is injected — so the whole ladder is
 * unit-testable off-device.
 *
 * The persisted port is tried FIRST: that is what makes the LOCATION URL stable across
 * restarts. A bare ladder is not enough — if 49152 were busy on first run the user would
 * register :49153 in Home Assistant, and a later start (with 49152 now free) would move the
 * renderer back to 49152 and permanently break that manually-added URL.
 */
object RendererPortPicker {
    const val FIRST = 49152
    const val LAST = 49161

    /**
     * @param preferred the previously-bound port, or null on first ever start. Values outside
     *        1024..65535 (a corrupt pref) are ignored as if absent — an invalid bind attempt
     *        must never be made from garbage input.
     * @param bind attempts a bind and returns the bound port, or null if the port is taken.
     *             Called with 0 to request an ephemeral port, which must succeed.
     */
    fun choose(preferred: Int?, bind: (Int) -> Int?): Int {
        val valid = preferred?.takeIf { it in 1024..65535 }
        if (valid != null) bind(valid)?.let { return it }
        for (port in FIRST..LAST) {
            if (port == valid) continue          // already tried above; don't retry
            bind(port)?.let { return it }
        }
        return bind(0) ?: error("ephemeral bind must succeed")
    }
}
