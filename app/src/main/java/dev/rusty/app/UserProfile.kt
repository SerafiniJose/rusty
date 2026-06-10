package dev.rusty.app

/** Resolved public profile for the connected Spotify account. */
data class UserProfile(
    val displayName: String?,
    val avatarUrl: String?
)
