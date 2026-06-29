package dev.rusty.app

import android.content.SharedPreferences

/**
 * The ordered set of top-level features. Declaration order is the switch-ring order. SP0 registers
 * only Spotify; HA/Camera are appended by their own sub-projects.
 */
object FeatureRegistry {
    val all: List<Feature> = listOf(SpotifyFeature, HomeAssistantFeature)

    /** The enabled feature ids, in ring order. */
    fun enabledIds(prefs: SharedPreferences): List<FeatureId> =
        FeatureRing.enabled(all.map { it.id }) { id -> byId(id).isEnabled(prefs) }

    fun byId(id: FeatureId): Feature = all.first { it.id == id }
}
