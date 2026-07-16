package dev.rusty.app.renderer

import android.content.SharedPreferences

/** Which interruption the renderer took against Spotify — the recorded owner drives the release. */
enum class SpotifyInterruption { PAUSE, DUCK }

/** Storage seam so all renderer-prefs decisions stay pure and JVM-testable. */
interface RendererPrefsStore {
    fun getString(key: String): String?
    fun getInt(key: String): Int?
    fun getLong(key: String, def: Long): Long
    /** Applies [block] as ONE atomic transaction. */
    fun edit(block: (MutableMap<String, Any?>) -> Unit)
}

/** The one SharedPreferences adapter — used by the service, the controller and the settings panel. */
internal class SharedPrefsRendererStore(private val prefs: SharedPreferences) : RendererPrefsStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    override fun edit(block: (MutableMap<String, Any?>) -> Unit) {
        val changes = mutableMapOf<String, Any?>()
        block(changes)
        prefs.edit().apply {
            changes.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Boolean -> putBoolean(k, v)
                    null -> remove(k)
                    else -> error("unsupported pref type for $k")
                }
            }
        }.apply()   // one atomic transaction
    }
}

sealed interface RenameResult {
    /** Blank/whitespace-only input — rejected, previous name kept. */
    object Blank : RenameResult
    /** Same name as before — no CONFIGID bump, no announcement. */
    object Unchanged : RenameResult
    data class Renamed(val name: String, val configId: Long) : RenameResult
}

object RendererPrefs {
    const val KEY_NAME = "dlna_renderer_name"
    const val KEY_PORT = "dlna_renderer_port"
    const val KEY_CONFIGID = "dlna_renderer_configid"
    const val KEY_BOOTID = "dlna_renderer_bootid"
    const val KEY_MIX_MODE = "dlna_mix_mode"
    const val KEY_FADE_MS = "dlna_fade_ms"
    const val DEFAULT_NAME = "Rusty Media Player"

    /** Default announcement fade: 500 ms — snappy enough not to delay announcements. */
    const val DEFAULT_FADE_MS = 500L
    private const val FADE_CEILING_MS = 10_000L

    // UDA 2.0: BOOTID is a non-negative 31-bit integer; CONFIGID must stay below 2^24.
    // A corrupt pref must wrap to a valid value, never emit an invalid header.
    private const val BOOTID_CEILING = 0x7FFF_FFFFL
    private const val CONFIGID_CEILING = 0xFF_FFFFL

    fun name(store: RendererPrefsStore): String = store.getString(KEY_NAME) ?: DEFAULT_NAME

    fun configId(store: RendererPrefsStore): Long = store.getLong(KEY_CONFIGID, 1L)

    fun port(store: RendererPrefsStore): Int? = store.getInt(KEY_PORT)

    fun persistPort(store: RendererPrefsStore, port: Int) = store.edit { it[KEY_PORT] = port }

    /** UPnP: a (re)started or re-joining device must present a NEW BootID. */
    fun bumpBootId(store: RendererPrefsStore): Long {
        val prev = store.getLong(KEY_BOOTID, 0L)
        val next = if (prev < 0L || prev >= BOOTID_CEILING) 1L else prev + 1
        store.edit { it[KEY_BOOTID] = next }
        return next
    }

    fun mixMode(store: RendererPrefsStore): SpotifyInterruption =
        if (store.getString(KEY_MIX_MODE) == "duck") SpotifyInterruption.DUCK else SpotifyInterruption.PAUSE

    fun setMixMode(store: RendererPrefsStore, mode: SpotifyInterruption) =
        store.edit { it[KEY_MIX_MODE] = if (mode == SpotifyInterruption.DUCK) "duck" else "pause" }

    /** How long Spotify fades out before / back in after an announcement. 0 = off (instant). */
    fun fadeMs(store: RendererPrefsStore): Long =
        store.getLong(KEY_FADE_MS, DEFAULT_FADE_MS).coerceIn(0L, FADE_CEILING_MS)

    fun setFadeMs(store: RendererPrefsStore, ms: Long) =
        store.edit { it[KEY_FADE_MS] = ms.coerceIn(0L, FADE_CEILING_MS) }

    private fun nextConfigId(store: RendererPrefsStore): Long {
        val prev = configId(store)
        return if (prev < 0L || prev >= CONFIGID_CEILING) 1L else prev + 1
    }

    /**
     * Installs predating the separate name served the Spotify receiver name; they now serve
     * [DEFAULT_NAME]. That is a device-description change with no user action, so CONFIGID must
     * bump once — otherwise control points may cache the old name forever.
     */
    fun migrateIfNeeded(store: RendererPrefsStore): Boolean {
        if (store.getString(KEY_NAME) != null) return false
        val nextConfig = nextConfigId(store)
        store.edit {
            it[KEY_NAME] = DEFAULT_NAME
            it[KEY_CONFIGID] = nextConfig
        }
        return true
    }

    fun rename(store: RendererPrefsStore, raw: String): RenameResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RenameResult.Blank
        if (trimmed == name(store)) return RenameResult.Unchanged
        val nextConfig = nextConfigId(store)
        store.edit {
            it[KEY_NAME] = trimmed
            it[KEY_CONFIGID] = nextConfig
        }
        return RenameResult.Renamed(trimmed, nextConfig)
    }
}
