package dev.rusty.app

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * Storage for credentials — currently the Immich API key.
 *
 * Split out behind an interface for two reasons: the real implementation needs a [Context] and the
 * Android KeyStore (so it cannot run in a JVM unit test), and settings logic that merely *reads* a
 * credential should not have to know where it is kept. Tests use [InMemorySecretStore].
 *
 * Secrets deliberately do NOT live in the app's regular [android.content.SharedPreferences]: that
 * file is plaintext XML, and it is what `adb backup` and cloud backup copy off the device.
 */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)

    companion object {
        @Volatile private var instance: SecretStore? = null

        /** Process-wide store. Building it does key derivation and file I/O, so it is cached. */
        fun of(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: AndroidSecretStore.create(context.applicationContext).also { instance = it }
            }
    }
}

/** Test double — also the last-resort fallback when the KeyStore is unusable. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String): String? = values[name]
    override fun put(name: String, value: String) { values[name] = value }
    override fun remove(name: String) { values.remove(name) }
}

/**
 * [SecretStore] backed by [EncryptedSharedPreferences] (AES-256 GCM values, AES-256 SIV keys) under
 * a hardware-backed master key in the Android KeyStore.
 *
 * Two failure modes are handled explicitly, because both are field-reported crashes rather than
 * theoretical ones:
 *
 *  - **Corrupt keystore / undecryptable file.** A restored backup, a device with a flaky
 *    KeyStore implementation, or a master key rotated out from under the file all make
 *    [EncryptedSharedPreferences.create] throw. Recovery is to delete the encrypted file and the
 *    master key and start over — the API key is re-enterable, so destroying it is strictly better
 *    than a boot loop.
 *  - **KeyStore entirely unavailable.** If the retry also fails we fall back to
 *    [InMemorySecretStore]. That loses the key on process death (the user re-enters it), which is
 *    an annoyance; writing it back to plaintext disk would be a silent security downgrade, so we
 *    do not.
 */
private object AndroidSecretStore {
    private const val TAG = "SecretStore"
    private const val FILE_NAME = "rusty_secrets"

    fun create(context: Context): SecretStore {
        val store = tryOpen(context) ?: run {
            Log.w(TAG, "encrypted prefs unusable — recreating")
            reset(context)
            tryOpen(context)
        }
        if (store == null) {
            Log.e(TAG, "KeyStore unavailable — secrets will not persist this session")
            return InMemorySecretStore()
        }
        migratePlaintextSecrets(context, store)
        return store
    }

    private fun tryOpen(context: Context): SecretStore? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        object : SecretStore {
            override fun get(name: String): String? = prefs.getString(name, null)
            override fun put(name: String, value: String) = prefs.edit().putString(name, value).apply()
            override fun remove(name: String) = prefs.edit().remove(name).apply()
        }
    } catch (t: Throwable) {
        // Deliberately Throwable: this path has thrown GeneralSecurityException, IOException and
        // (on some OEM builds) raw IllegalStateException / KeyStoreException from native code.
        Log.w(TAG, "could not open encrypted prefs", t)
        null
    }

    /** Drop the encrypted file and its master key so the next open starts from a clean slate. */
    private fun reset(context: Context) {
        runCatching {
            context.deleteSharedPreferences(FILE_NAME)
            File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").delete()
        }
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    /**
     * One-way migration for installs that stored the API key in plaintext prefs before this
     * existed. Copies the value across, then removes the plaintext original — leaving it behind
     * would defeat the entire change, since that is the file backups capture.
     *
     * Only migrates when the encrypted store has no value yet, so a later key change made through
     * settings can never be reverted by a stale plaintext leftover.
     */
    private fun migratePlaintextSecrets(context: Context, store: SecretStore) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(SlideshowSettings.KEY_API_KEY, null)
        if (legacy.isNullOrBlank()) return
        if (store.get(SlideshowSettings.KEY_API_KEY).isNullOrBlank()) {
            store.put(SlideshowSettings.KEY_API_KEY, legacy)
        }
        prefs.edit().remove(SlideshowSettings.KEY_API_KEY).apply()
        Log.i(TAG, "migrated API key out of plaintext preferences")
    }

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** The app-wide plaintext prefs file — read once, only to migrate the key out of it. */
    private const val PREFS_NAME = "spotify_receiver_prefs"
}
