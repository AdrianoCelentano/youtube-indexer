package com.ytindexer.shared.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

private fun fixedClock(now: Long = NOW) = Clock { now }

private fun tokens(
    access: String = "access-1",
    refresh: String? = "refresh-1",
    expiresAt: Long = NOW + 3600,
) = OAuthTokens(access, refresh, expiresAt)

/** Records how many times a refresh was attempted. */
private class CountingRefresher(
    private val result: (String) -> OAuthTokens,
) : TokenRefresher {
    var calls = 0
        private set

    override suspend fun refresh(refreshToken: String): OAuthTokens {
        calls++
        return result(refreshToken)
    }
}

class AuthManagerTest {
    @Test
    fun returns_stored_token_without_refreshing_when_still_valid() =
        runTest {
            val refresher = CountingRefresher { error("should not refresh") }
            val manager = AuthManager(InMemoryTokenStore(tokens()), refresher, fixedClock())

            assertEquals("access-1", manager.accessToken())
            assertEquals(0, refresher.calls)
        }

    @Test
    fun refreshes_when_access_token_expired() =
        runTest {
            val store = InMemoryTokenStore(tokens(expiresAt = NOW - 10))
            val refresher =
                CountingRefresher { OAuthTokens("access-2", "refresh-1", NOW + 3600) }
            val manager = AuthManager(store, refresher, fixedClock())

            assertEquals("access-2", manager.accessToken())
            assertEquals(1, refresher.calls)
            assertEquals("access-2", store.load()?.accessToken)
        }

    @Test
    fun refreshes_within_leeway_before_actual_expiry() =
        runTest {
            // Not yet expired, but inside the leeway window -- a request could otherwise
            // land after expiry.
            val expiring = tokens(expiresAt = NOW + 5)
            val refresher = CountingRefresher { OAuthTokens("access-2", "refresh-1", NOW + 3600) }
            val manager = AuthManager(InMemoryTokenStore(expiring), refresher, fixedClock())

            assertEquals("access-2", manager.accessToken())
            assertEquals(1, refresher.calls)
        }

    @Test
    fun keeps_existing_refresh_token_when_google_omits_it() =
        runTest {
            val store = InMemoryTokenStore(tokens(expiresAt = NOW - 10))
            // Google does not return refresh_token on a refresh response.
            val refresher = CountingRefresher { OAuthTokens("access-2", null, NOW + 3600) }
            val manager = AuthManager(store, refresher, fixedClock())

            manager.accessToken()

            assertEquals(
                "refresh-1",
                store.load()?.refreshToken,
                "dropping the refresh token would silently end the session at next expiry",
            )
        }

    @Test
    fun concurrent_callers_trigger_only_one_refresh() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val refresher =
                object : TokenRefresher {
                    var calls = 0
                        private set

                    override suspend fun refresh(refreshToken: String): OAuthTokens {
                        calls++
                        gate.await()
                        return OAuthTokens("access-2", "refresh-1", NOW + 3600)
                    }
                }
            val manager =
                AuthManager(InMemoryTokenStore(tokens(expiresAt = NOW - 10)), refresher, fixedClock())

            val requests = List(5) { async { manager.accessToken() } }
            // Let all five actually start and park on their suspension points before
            // releasing the refresh. Completing the gate first would let the first
            // caller run start-to-finish before the others began, so the test would
            // pass even without the lock.
            runCurrent()
            gate.complete(Unit)
            val results = requests.awaitAll()

            assertEquals(1, refresher.calls, "N concurrent callers must share one refresh")
            assertTrue(results.all { it == "access-2" })
        }

    @Test
    fun clears_session_when_refresh_token_is_rejected() =
        runTest {
            val store = InMemoryTokenStore(tokens(expiresAt = NOW - 10))
            val refresher =
                TokenRefresher { throw AuthError.RefreshRejected("invalid_grant") }
            val manager = AuthManager(store, refresher, fixedClock())

            assertFailsWith<AuthError.RefreshRejected> { manager.accessToken() }
            assertNull(store.load(), "a dead grant must not leave unusable credentials behind")
            assertEquals(false, manager.isSignedIn())
        }

    @Test
    fun keeps_credentials_when_refresh_fails_transiently() =
        runTest {
            val store = InMemoryTokenStore(tokens(expiresAt = NOW - 10))
            val refresher = TokenRefresher { throw AuthError.Network(null) }
            val manager = AuthManager(store, refresher, fixedClock())

            assertFailsWith<AuthError.Network> { manager.accessToken() }
            assertTrue(
                store.load() != null,
                "a flaky network must not sign the user out",
            )
        }

    @Test
    fun throws_not_signed_in_when_no_credentials_stored() =
        runTest {
            val manager =
                AuthManager(InMemoryTokenStore(), TokenRefresher { error("unused") }, fixedClock())

            assertFailsWith<AuthError.NotSignedIn> { manager.accessToken() }
        }

    @Test
    fun expired_token_with_no_refresh_token_signs_out() =
        runTest {
            val store = InMemoryTokenStore(tokens(refresh = null, expiresAt = NOW - 10))
            val manager = AuthManager(store, TokenRefresher { error("unused") }, fixedClock())

            assertFailsWith<AuthError.NotSignedIn> { manager.accessToken() }
            assertNull(store.load())
        }

    @Test
    fun sign_out_clears_stored_credentials() =
        runTest {
            val store = InMemoryTokenStore(tokens())
            val manager = AuthManager(store, TokenRefresher { error("unused") }, fixedClock())

            manager.signOut()

            assertNull(store.load())
            assertEquals(false, manager.isSignedIn())
        }

    @Test
    fun on_signed_in_persists_and_is_immediately_usable() =
        runTest {
            val store = InMemoryTokenStore()
            val manager = AuthManager(store, TokenRefresher { error("unused") }, fixedClock())

            manager.onSignedIn(tokens(access = "fresh"))

            assertEquals("fresh", manager.accessToken())
            assertEquals("fresh", store.load()?.accessToken)
        }
}
