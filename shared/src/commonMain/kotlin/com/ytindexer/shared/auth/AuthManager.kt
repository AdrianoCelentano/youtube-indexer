package com.ytindexer.shared.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether a usable session exists, for UI to observe. */
enum class AuthState { UNKNOWN, SIGNED_OUT, SIGNED_IN }

/**
 * Owns the OAuth session: caches tokens, refreshes them before expiry, and clears the
 * session when Google says the grant is gone.
 *
 * Everything that needs a bearer token should go through [accessToken] rather than
 * reading [TokenStore] directly, so refresh stays in one place.
 */
class AuthManager(
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AuthState.UNKNOWN)

    /** Cached copy so the common path avoids hitting encrypted storage every request. */
    private var cached: OAuthTokens? = null
    private var loadedFromStore = false

    val state: Flow<AuthState> = _state.asStateFlow()

    /**
     * Returns a valid access token, refreshing first if the current one is expired or
     * close to it.
     *
     * The whole read-check-refresh sequence is inside a mutex, so N concurrent API calls
     * on a cold token trigger exactly one refresh; the rest wait and reuse the result.
     *
     * @throws AuthError.NotSignedIn if there are no stored credentials.
     * @throws AuthError.RefreshRejected if the grant is dead. The session is cleared
     *   first, so callers can route straight to the sign-in screen.
     * @throws AuthError.Network on transient failures; stored credentials are kept.
     */
    suspend fun accessToken(): String =
        mutex.withLock {
            val current = currentTokens() ?: throw AuthError.NotSignedIn

            if (!current.isExpired(clock.nowEpochSeconds())) {
                return@withLock current.accessToken
            }

            refreshLocked(current)
        }

    /**
     * Refreshes even when the cached token still looks valid.
     *
     * For the case where the API returns 401 despite the expiry check passing: the token
     * was revoked server-side, or the device clock is skewed.
     *
     * @param staleToken the token that was just rejected. If the cached token no longer
     *   matches it, another caller already refreshed while this one waited for the lock,
     *   so the fresh token is returned instead. Without this, N concurrent 401s would
     *   trigger N refreshes.
     */
    suspend fun forceRefresh(staleToken: String? = null): String =
        mutex.withLock {
            val current = currentTokens() ?: throw AuthError.NotSignedIn

            if (staleToken != null && current.accessToken != staleToken) {
                return@withLock current.accessToken
            }

            refreshLocked(current)
        }

    /** Refreshes and persists. Must be called while holding [mutex]. */
    private suspend fun refreshLocked(current: OAuthTokens): String {
        val refreshToken =
            current.refreshToken ?: run {
                // Access token is dead and there's nothing to refresh with.
                clearLocked()
                throw AuthError.NotSignedIn
            }

        val refreshed =
            try {
                refresher.refresh(refreshToken)
            } catch (e: AuthError.RefreshRejected) {
                // The grant is gone for good -- don't keep credentials that can never
                // work again.
                clearLocked()
                throw e
            }

        // Google omits refresh_token when refreshing, so fall back to the one we already
        // hold. Dropping it here would silently end the session at the next expiry.
        val merged = refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken)
        persistLocked(merged)
        return merged.accessToken
    }

    /** Stores credentials obtained from a completed sign-in flow. */
    suspend fun onSignedIn(tokens: OAuthTokens) =
        mutex.withLock {
            persistLocked(tokens)
        }

    /** Drops all credentials. Safe to call when already signed out. */
    suspend fun signOut() =
        mutex.withLock {
            clearLocked()
        }

    /** True if credentials exist, regardless of whether the access token is currently fresh. */
    suspend fun isSignedIn(): Boolean =
        mutex.withLock {
            currentTokens() != null
        }

    private suspend fun currentTokens(): OAuthTokens? {
        if (!loadedFromStore) {
            cached = tokenStore.load()
            loadedFromStore = true
            _state.value = if (cached != null) AuthState.SIGNED_IN else AuthState.SIGNED_OUT
        }
        return cached
    }

    private suspend fun persistLocked(tokens: OAuthTokens) {
        tokenStore.save(tokens)
        cached = tokens
        loadedFromStore = true
        _state.value = AuthState.SIGNED_IN
    }

    private suspend fun clearLocked() {
        tokenStore.clear()
        cached = null
        loadedFromStore = true
        _state.value = AuthState.SIGNED_OUT
    }
}
