package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live tab-strip sync used when a feature is enabled/disabled from the General tab while the
 * settings dialog is OPEN: the strip must gain/lose the feature's tab immediately, not on reopen.
 *
 * Both lists are always subsequences of the same master order (General, Screensaver, then ring
 * order), so applying [SettingsTabSyncOps.removals] at their positions (descending) followed by
 * [SettingsTabSyncOps.insertions] at their positions (ascending) transforms `current` into `target`.
 */
class SettingsTabSyncTest {

    private fun apply(current: List<SettingsTabKey>, ops: SettingsTabSyncOps): List<SettingsTabKey> {
        val list = current.toMutableList()
        ops.removals.forEach { list.removeAt(it) }
        ops.insertions.forEach { (key, position) -> list.add(position, key) }
        return list
    }

    @Test fun noChangeYieldsNoOps() {
        val tabs = listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.SPOTIFY)
        val ops = settingsTabSyncOps(tabs, tabs)
        assertEquals(emptyList<Int>(), ops.removals)
        assertEquals(emptyList<Pair<SettingsTabKey, Int>>(), ops.insertions)
    }

    @Test fun enablingAFeatureInsertsItsTabAtRingPosition() {
        val current = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER,
            SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT,
        )
        val target = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
            SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT,
        )
        val ops = settingsTabSyncOps(current, target)
        assertEquals(emptyList<Int>(), ops.removals)
        assertEquals(listOf(SettingsTabKey.DLNA_PLAYER to 2), ops.insertions)
        assertEquals(target, apply(current, ops))
    }

    @Test fun disablingAFeatureRemovesItsTab() {
        val current = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
            SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT,
        )
        val target = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER,
            SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT,
        )
        val ops = settingsTabSyncOps(current, target)
        assertEquals(listOf(2), ops.removals)
        assertEquals(emptyList<Pair<SettingsTabKey, Int>>(), ops.insertions)
        assertEquals(target, apply(current, ops))
    }

    @Test fun removalsAreDescendingSoPositionsStayValidWhileApplying() {
        val current = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
            SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT,
        )
        val target = listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.SPOTIFY)
        val ops = settingsTabSyncOps(current, target)
        assertEquals(listOf(4, 2), ops.removals)
        assertEquals(target, apply(current, ops))
    }

    @Test fun simultaneousAddAndRemoveConverges() {
        val current = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.HOME_ASSISTANT,
        )
        val target = listOf(
            SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
            SettingsTabKey.SPOTIFY,
        )
        val ops = settingsTabSyncOps(current, target)
        assertEquals(target, apply(current, ops))
    }
}
