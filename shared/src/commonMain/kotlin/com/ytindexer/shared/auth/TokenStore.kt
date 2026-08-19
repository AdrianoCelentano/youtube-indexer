package com.ytindexer.shared.auth

/**
 * Persists OAuth credentials across process death.
 *
 * An interface rather than an `expect`/`actual` so tests can substitute an in-memory
 * fake without a platform stub. The Android implementation
 * ([com.ytindexer.shared.auth.EncryptedTokenStore]) is backed by the Keystore.
 *
 * Implementations must treat the refresh token as a secret: never log it, and never
 * write it to plain shared preferences or a file.
 */
interface TokenStore {
    suspend fun load(): OAuthTokens?

    suspend fun save(tokens: OAuthTokens)

    suspend fun clear()
}

/** In-memory store. Intended for tests and previews -- it does not survive process death. */
class InMemoryTokenStore(
    initial: OAuthTokens? = null,
) : TokenStore {
    private var tokens: OAuthTokens? = initial

    override suspend fun load(): OAuthTokens? = tokens

    override suspend fun save(tokens: OAuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
