package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(initial: Map<String, Any?> = emptyMap()) : RendererPrefsStore {
    val map = initial.toMutableMap()
    var transactions = 0
    override fun getString(key: String): String? = map[key] as String?
    override fun getInt(key: String): Int? = map[key] as Int?
    override fun getLong(key: String, def: Long): Long = (map[key] as Long?) ?: def
    override fun edit(block: (MutableMap<String, Any?>) -> Unit) {
        transactions++
        block(map)
    }
}

class RendererPrefsTest {

    @Test
    fun `name defaults to Rusty Media Player`() {
        assertEquals("Rusty Media Player", RendererPrefs.name(FakeStore()))
    }

    @Test
    fun `name returns the persisted value`() {
        assertEquals("Kitchen", RendererPrefs.name(FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen"))))
    }

    @Test
    fun `configId defaults to 1 and port defaults to null`() {
        val store = FakeStore()
        assertEquals(1L, RendererPrefs.configId(store))
        assertNull(RendererPrefs.port(store))
    }

    @Test
    fun `persistPort stores the bound port`() {
        val store = FakeStore()
        RendererPrefs.persistPort(store, 49153)
        assertEquals(49153, RendererPrefs.port(store))
    }

    @Test
    fun `bumpBootId increments and persists`() {
        val store = FakeStore(mapOf(RendererPrefs.KEY_BOOTID to 4L))
        assertEquals(5L, RendererPrefs.bumpBootId(store))
        assertEquals(5L, store.getLong(RendererPrefs.KEY_BOOTID, 0L))
    }

    // -- identifier bounds (UDA: BOOTID 31-bit non-negative, CONFIGID 0..2^24-1) --------

    @Test
    fun `bumpBootId wraps to 1 from the 31-bit ceiling and from corrupt values`() {
        assertEquals(1L, RendererPrefs.bumpBootId(FakeStore(mapOf(RendererPrefs.KEY_BOOTID to 0x7FFF_FFFFL))))
        assertEquals(1L, RendererPrefs.bumpBootId(FakeStore(mapOf(RendererPrefs.KEY_BOOTID to -7L))))
    }

    @Test
    fun `configId bump wraps to 1 from the 24-bit ceiling and from corrupt values`() {
        val atCeiling = FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen", RendererPrefs.KEY_CONFIGID to 0xFF_FFFFL))
        assertEquals(RenameResult.Renamed("Den", 1L), RendererPrefs.rename(atCeiling, "Den"))

        val corrupt = FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen", RendererPrefs.KEY_CONFIGID to -3L))
        assertEquals(RenameResult.Renamed("Den", 1L), RendererPrefs.rename(corrupt, "Den"))
    }

    // -- mix mode ------------------------------------------------------------------------

    @Test
    fun `mix mode defaults to PAUSE, persists DUCK, and ignores garbage`() {
        val store = FakeStore()
        assertEquals(SpotifyInterruption.PAUSE, RendererPrefs.mixMode(store))
        RendererPrefs.setMixMode(store, SpotifyInterruption.DUCK)
        assertEquals(SpotifyInterruption.DUCK, RendererPrefs.mixMode(store))
        assertEquals("duck", store.getString(RendererPrefs.KEY_MIX_MODE))
        assertEquals(
            SpotifyInterruption.PAUSE,
            RendererPrefs.mixMode(FakeStore(mapOf(RendererPrefs.KEY_MIX_MODE to "banana")))
        )
    }

    // -- fade duration ---------------------------------------------------------------------

    @Test fun fadeMs_defaultsToMedium() {
        val store = FakeStore()
        assertEquals(500L, RendererPrefs.fadeMs(store))
    }

    @Test fun fadeMs_roundTrips() {
        val store = FakeStore()
        RendererPrefs.setFadeMs(store, 250L)
        assertEquals(250L, RendererPrefs.fadeMs(store))
        RendererPrefs.setFadeMs(store, 0L)
        assertEquals(0L, RendererPrefs.fadeMs(store))
    }

    @Test fun fadeMs_corruptValueClamps() {
        val store = FakeStore()
        RendererPrefs.setFadeMs(store, -50L)
        assertEquals(0L, RendererPrefs.fadeMs(store))
        RendererPrefs.setFadeMs(store, 60_000L)
        assertEquals(10_000L, RendererPrefs.fadeMs(store))
    }

    // -- upgrade migration ----------------------------------------------------------------

    @Test
    fun `migration seeds the default name and bumps configId exactly once`() {
        val store = FakeStore(mapOf(RendererPrefs.KEY_CONFIGID to 1L))

        assertTrue(RendererPrefs.migrateIfNeeded(store))
        assertEquals("Rusty Media Player", RendererPrefs.name(store))
        assertEquals(2L, RendererPrefs.configId(store))
        assertEquals(1, store.transactions)

        assertFalse("must not migrate twice", RendererPrefs.migrateIfNeeded(store))
        assertEquals(2L, RendererPrefs.configId(store))
        assertEquals(1, store.transactions)
    }

    // -- rename -----------------------------------------------------------------------------

    @Test
    fun `rename persists the name and bumps configId in one transaction`() {
        val store = FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen", RendererPrefs.KEY_CONFIGID to 3L))

        val result = RendererPrefs.rename(store, "  Living Room  ")

        assertEquals(RenameResult.Renamed("Living Room", 4L), result)
        assertEquals("Living Room", RendererPrefs.name(store))
        assertEquals(4L, RendererPrefs.configId(store))
        assertEquals("name + configId must be ONE atomic edit", 1, store.transactions)
    }

    @Test
    fun `rename to the same name is a no-op`() {
        val store = FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen", RendererPrefs.KEY_CONFIGID to 3L))
        assertEquals(RenameResult.Unchanged, RendererPrefs.rename(store, "Kitchen"))
        assertEquals(3L, RendererPrefs.configId(store))
        assertEquals(0, store.transactions)
    }

    @Test
    fun `blank rename is rejected and leaves the previous name intact`() {
        val store = FakeStore(mapOf(RendererPrefs.KEY_NAME to "Kitchen", RendererPrefs.KEY_CONFIGID to 3L))
        assertEquals(RenameResult.Blank, RendererPrefs.rename(store, "   "))
        assertEquals("Kitchen", RendererPrefs.name(store))
        assertEquals(0, store.transactions)
    }
}
