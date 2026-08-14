package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the single list of pref keys wiped on a session reset, shared by sign-out AND the
 *  URL-change reset (settings + fragment), so the paths can never drift. KEY_URL must NOT be in it. */
class HaServerResetKeysTest {

    @Test
    fun containsEveryServerScopedKey_butNeverTheUrl() {
        val keys = HomeAssistantFeature.SERVER_RESET_KEYS
        assertTrue(keys.contains("ha_dashboards_cache"))
        assertTrue(keys.contains("ha_dashboards_origin"))
        assertTrue(keys.contains("ha_selected_dashboards"))
        assertTrue(keys.contains("ha_active_dashboard_origin"))
        assertTrue(keys.contains("ha_active_dashboard_path"))
        assertTrue(keys.contains("ha_selected_theme"))
        // The account name is a mirror of the signed-in identity, so it is server-scoped too: a
        // different server (or a sign-out) must not leave the previous account's name on the
        // Services & status page.
        assertTrue(keys.contains("ha_account_name"))
        assertFalse("the server URL must survive sign-out", keys.contains("ha_url"))
        assertEquals(7, keys.size)
    }
}
