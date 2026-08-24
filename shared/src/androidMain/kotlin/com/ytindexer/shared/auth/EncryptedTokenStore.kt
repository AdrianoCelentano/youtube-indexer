package com.ytindexer.shared.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TokenStore] backed by `EncryptedSharedPreferences`, whose master key lives in the
 * Android Keystore and never leaves it.
 *
 * **Opening it is allowed to fail, and must not crash the app.** The encrypted file and
 * the Keystore key can get out of step in ways that are entirely outside the app's
 * control:
 *
 * - Android's auto-backup restores the *file* on a fresh install, but Keystore keys are
 *   never backed up. The restored file is then undecryptable.
 * - Keys are invalidated by some device state changes, and a few OEM Keystore
 *   implementations simply misbehave.
 *
 * The stakes are asymmetric: the worst case for discarding this data is that the user
 * signs in again, whereas the worst case for throwing is an app that cannot start at all
 * and can only be fixed by reinstalling. So a failure deletes the unreadable file and
 * retries, and if even that fails the store degrades to memory -- the session lasts only
 * until the process dies, but the app runs.
 */
class EncryptedTokenStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    fileName: String = DEFAULT_FILE_NAME,
) : TokenStore {
    private val appContext = context.applicationContext
    private val prefsFileName = fileName

    /** Used only when encrypted storage cannot be opened at all. */
    private val inMemory = mutableMapOf<String, Any?>()

    // Built lazily: constructing the master key touches the Keystore, which is too slow
    // for a constructor called during DI graph setup.
    private val prefs: SharedPreferences? by lazy { openOrRecover() }

    private fun openEncrypted(): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        return EncryptedSharedPreferences.create(
            appContext,
            prefsFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun openOrRecover(): SharedPreferences? =
        try {
            openEncrypted()
        } catch (
            @Suppress("SwallowedException") firstFailure: Exception,
        ) {
            // Deliberately broad: the failure modes span GeneralSecurityException,
            // IOException and OEM-specific runtime exceptions, and the response is the
            // same for all of them. Being precise here would mean crashing on the one
            // variant that was not anticipated.
            recover()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun recover(): SharedPreferences? {
        // The file cannot be decrypted, so its contents are already lost. Removing it
        // lets a fresh keypair be created rather than failing forever.
        runCatching { appContext.deleteSharedPreferences(prefsFileName) }

        return try {
            openEncrypted()
        } catch (
            @Suppress("SwallowedException") secondFailure: Exception,
        ) {
            // Keystore itself is unusable on this device. Running without persistence
            // beats not running.
            null
        }
    }

    override suspend fun load(): OAuthTokens? =
        withContext(ioDispatcher) {
            val accessToken = getString(KEY_ACCESS_TOKEN) ?: return@withContext null

            OAuthTokens(
                accessToken = accessToken,
                refreshToken = getString(KEY_REFRESH_TOKEN),
                expiresAtEpochSeconds = getLong(KEY_EXPIRES_AT),
                scopes = getStringSet(KEY_SCOPES),
            )
        }

    override suspend fun save(tokens: OAuthTokens) {
        withContext(ioDispatcher) {
            val store = prefs
            if (store == null) {
                inMemory[KEY_ACCESS_TOKEN] = tokens.accessToken
                inMemory[KEY_REFRESH_TOKEN] = tokens.refreshToken
                inMemory[KEY_EXPIRES_AT] = tokens.expiresAtEpochSeconds
                inMemory[KEY_SCOPES] = tokens.scopes
                return@withContext
            }

            val editor = store.edit()
            editor.putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            editor.putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            editor.putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochSeconds)
            editor.putStringSet(KEY_SCOPES, tokens.scopes)
            // commit() rather than apply(): callers must be able to rely on the tokens
            // being on disk once this returns. We're already off the main thread.
            editor.commit()
        }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            inMemory.clear()
            prefs?.edit()?.clear()?.commit()
        }
    }

    private fun getString(key: String): String? = prefs?.getString(key, null) ?: inMemory[key] as? String

    private fun getLong(key: String): Long = prefs?.getLong(key, 0L) ?: (inMemory[key] as? Long ?: 0L)

    @Suppress("UNCHECKED_CAST")
    private fun getStringSet(key: String): Set<String> =
        prefs?.getStringSet(key, emptySet()).orEmpty().ifEmpty {
            (inMemory[key] as? Set<String>).orEmpty()
        }

    companion object {
        const val DEFAULT_FILE_NAME: String = "yt_indexer_auth"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SCOPES = "scopes"
    }
}
