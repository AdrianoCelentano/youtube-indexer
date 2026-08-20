package com.ytindexer.android.auth

import android.content.Intent
import com.ytindexer.shared.auth.AuthError
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.auth.InMemoryTokenStore
import com.ytindexer.shared.auth.OAuthTokens
import com.ytindexer.shared.auth.TokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val NOW = 1_000_000L

private fun tokens(
    refresh: String? = "refresh-1",
    scopes: Set<String> = setOf(GoogleAuthConfig.YOUTUBE_READONLY_SCOPE),
) = OAuthTokens("access-1", refresh, NOW + 3600, scopes)

private class FakeSignInFlow(
    private var result: (() -> OAuthTokens)? = null,
) : SignInFlow {
    var closed = false
        private set

    fun returns(tokens: OAuthTokens) {
        result = { tokens }
    }

    fun fails(error: AuthError) {
        result = { throw error }
    }

    // The real intent needs a Context; the ViewModel only ever hands it to a launcher.
    override fun createSignInIntent(): Intent = Intent("test.SIGN_IN")

    override suspend fun handleResult(data: Intent?): OAuthTokens =
        requireNotNull(result) { "no result configured" }.invoke()

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private fun authManager(initial: OAuthTokens? = null) =
        AuthManager(
            tokenStore = InMemoryTokenStore(initial),
            refresher = TokenRefresher { error("not used") },
            clock = Clock { NOW },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun starts_signed_out_when_no_credentials_stored() =
        runTest(dispatcher) {
            val vm = SignInViewModel(authManager(), FakeSignInFlow(), isConfigured = true)
            advanceUntilIdle()

            assertIs<SignInUiState.SignedOut>(vm.uiState.value)
        }

    @Test
    fun starts_signed_in_when_credentials_already_stored() =
        runTest(dispatcher) {
            val vm = SignInViewModel(authManager(tokens()), FakeSignInFlow(), isConfigured = true)
            advanceUntilIdle()

            assertIs<SignInUiState.SignedIn>(vm.uiState.value)
        }

    @Test
    fun reports_not_configured_when_client_id_missing() =
        runTest(dispatcher) {
            val vm = SignInViewModel(authManager(), FakeSignInFlow(), isConfigured = false)
            advanceUntilIdle()

            assertEquals(SignInUiState.NotConfigured, vm.uiState.value)
            assertNull(vm.signInIntent(), "must not attempt sign-in without a client ID")
        }

    @Test
    fun successful_sign_in_persists_tokens_and_reports_signed_in() =
        runTest(dispatcher) {
            val manager = authManager()
            val flow = FakeSignInFlow().apply { returns(tokens()) }
            val vm = SignInViewModel(manager, flow, isConfigured = true)
            advanceUntilIdle()

            vm.onSignInResult(Intent())
            advanceUntilIdle()

            assertIs<SignInUiState.SignedIn>(vm.uiState.value)
            assertEquals("access-1", manager.accessToken())
        }

    @Test
    fun sign_in_without_refresh_token_is_treated_as_failure() =
        runTest(dispatcher) {
            // Google omits refresh_token unless access_type=offline reaches it. Accepting
            // this would look like success and then die at the first expiry.
            val manager = authManager()
            val flow = FakeSignInFlow().apply { returns(tokens(refresh = null)) }
            val vm = SignInViewModel(manager, flow, isConfigured = true)
            advanceUntilIdle()

            vm.onSignInResult(Intent())
            advanceUntilIdle()

            val state = assertIs<SignInUiState.SignedOut>(vm.uiState.value)
            assertNotNull(state.message)
            assertEquals(false, manager.isSignedIn())
        }

    @Test
    fun flags_when_youtube_scope_was_not_granted() =
        runTest(dispatcher) {
            // The user can untick the YouTube permission on the consent screen.
            val flow = FakeSignInFlow().apply { returns(tokens(scopes = setOf("openid"))) }
            val vm = SignInViewModel(authManager(), flow, isConfigured = true)
            advanceUntilIdle()

            vm.onSignInResult(Intent())
            advanceUntilIdle()

            val state = assertIs<SignInUiState.SignedIn>(vm.uiState.value)
            assertEquals(false, state.grantedYouTubeScope)
        }

    @Test
    fun cancelled_sign_in_returns_to_signed_out_without_an_error_message() =
        runTest(dispatcher) {
            val flow = FakeSignInFlow().apply { fails(AuthError.NotSignedIn) }
            val vm = SignInViewModel(authManager(), flow, isConfigured = true)
            advanceUntilIdle()

            vm.onSignInResult(null)
            advanceUntilIdle()

            val state = assertIs<SignInUiState.SignedOut>(vm.uiState.value)
            assertNull(state.message, "cancelling is not an error the user needs told about")
        }

    @Test
    fun surfaces_message_when_sign_in_fails() =
        runTest(dispatcher) {
            val flow = FakeSignInFlow().apply { fails(AuthError.Network(null)) }
            val vm = SignInViewModel(authManager(), flow, isConfigured = true)
            advanceUntilIdle()

            vm.onSignInResult(Intent())
            advanceUntilIdle()

            assertNotNull(assertIs<SignInUiState.SignedOut>(vm.uiState.value).message)
        }

    @Test
    fun sign_out_clears_the_session() =
        runTest(dispatcher) {
            val manager = authManager(tokens())
            val vm = SignInViewModel(manager, FakeSignInFlow(), isConfigured = true)
            advanceUntilIdle()

            vm.signOut()
            advanceUntilIdle()

            assertIs<SignInUiState.SignedOut>(vm.uiState.value)
            assertEquals(false, manager.isSignedIn())
        }
}
