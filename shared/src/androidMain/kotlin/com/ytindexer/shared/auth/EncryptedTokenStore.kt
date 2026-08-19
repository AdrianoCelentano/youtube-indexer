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
 * Reads and writes hop to [Dispatchers.IO] because the first access performs Keystore
 * work and file I/O, which must not run on the main thread.
 */
class EncryptedTokenStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    fileName: String = DEFAULT_FILE_NAME,
) : TokenStore {
    private val appContext = context.applicationContext
    private val prefsFileName = fileName

    // Built lazily: constructing the master key touches the Keystore, which is too slow
    // for a constructor called during DI graph setup.
    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        EncryptedSharedPreferences.create(
            appContext,
            prefsFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun load(): OAuthTokens? =
        withContext(ioDispatcher) {
            val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
            val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

            OAuthTokens(
                accessToken = accessToken,
                refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
                expiresAtEpochSeconds = expiresAt,
                scopes = prefs.getStringSet(KEY_SCOPES, emptySet()).orEmpty(),
            )
        }

    override suspend fun save(tokens: OAuthTokens) {
        withContext(ioDispatcher) {
            val editor = prefs.edit()
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
            val editor = prefs.edit()
            editor.clear()
            editor.commit()
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME: String = "yt_indexer_auth"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SCOPES = "scopes"
    }
}
