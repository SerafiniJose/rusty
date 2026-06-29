package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the sign-in predicate that drives Connection-section + account-subtitle visibility. */
class HaSignInPredicateTest {

    @Test
    fun loadedWithAccount_isSignedIn() {
        val state = HaDiscovery.Loaded(
            emptyList(),
            HomeAssistantDashboards.HaAccount(name = "Jose", isAdmin = true),
        )
        assertTrue(HomeAssistantDashboards.isSignedIn(state))
    }

    @Test
    fun loadedWithoutAccount_isSignedIn() {
        assertTrue(HomeAssistantDashboards.isSignedIn(HaDiscovery.Loaded(emptyList(), null)))
    }

    @Test
    fun error_isNotSignedIn() {
        assertFalse(HomeAssistantDashboards.isSignedIn(HaDiscovery.Error("nope")))
    }

    @Test
    fun refreshing_isNotSignedIn() {
        assertFalse(HomeAssistantDashboards.isSignedIn(HaDiscovery.Refreshing))
    }

    @Test
    fun idle_isNotSignedIn() {
        assertFalse(HomeAssistantDashboards.isSignedIn(HaDiscovery.Idle))
    }
}
