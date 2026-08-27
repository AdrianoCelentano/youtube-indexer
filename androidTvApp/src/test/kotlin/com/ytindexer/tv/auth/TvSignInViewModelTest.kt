package com.ytindexer.tv.auth

import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.auth.GoogleDeviceCodeClient
import com.ytindexer.shared.auth.InMemoryTokenStore
import com.ytindexer.shared.auth.OAuthTokens
import com.ytindexer.shared.auth.TokenRefresher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private const val NOW = 1_000_000L

private const val DEVICE_CODE_RESPONSE =
    """{"device_code":"dc-1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device",""" +
        """"expires_in":1800,"interval":1}"""

private val AUTHORIZED =
    HttpStatusCode.OK to """{"access_token":"at","refresh_token":"rt","expires_in":3599,"scope":"scope-a"}"""
private val AUTHORIZED_NO_REFRESH_TOKEN = HttpStatusCode.OK to """{"access_token":"at","expires_in":3599}"""
private val PENDING = HttpStatusCode.BadRequest to """{"error":"authorization_pending"}"""
private val DENIED = HttpStatusCode.BadRequest to """{"error":"access_denied"}"""

/**
 * A [GoogleDeviceCodeClient]'s [HttpClient], stubbed to always answer `/device/code` the
 * same way and to work through [tokenResponses] in order for every `/token` poll,
 * repeating the last one once exhausted.
 */
private fun tokenClient(vararg tokenResponses: Pair<HttpStatusCode, String>): HttpClient {
    var index = 0
    return HttpClient(
        MockEngine { request ->
            val headers = headersOf("Content-Type", ContentType.Application.Json.toString())
            if (request.url.encodedPath.endsWith("/device/code")) {
                respond(DEVICE_CODE_RESPONSE, HttpStatusCode.OK, headers)
            } else {
                val lastIndex = tokenResponses.lastIndex.coerceAtLeast(0)
                val (status, body) = tokenResponses[index.coerceAtMost(lastIndex)]
                index++
                respond(body, status, headers)
            }
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TvSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private fun authManager(initial: OAuthTokens? = null) =
        AuthManager(
            tokenStore = InMemoryTokenStore(initial),
            refresher = TokenRefresher { error("not used") },
            clock = Clock { NOW },
        )

    private fun tokens(refresh: String? = "rt") = OAuthTokens("access-1", refresh, NOW + 3600)

    private fun viewModel(
        manager: AuthManager = authManager(),
        isConfigured: Boolean = true,
        vararg tokenResponses: Pair<HttpStatusCode, String>,
    ) = TvSignInViewModel(
        authManager = manager,
        deviceCodeClient = GoogleDeviceCodeClient(tokenClient(*tokenResponses), "tv-id", "tv-secret", Clock { NOW }),
        clock = Clock { NOW },
        isConfigured = isConfigured,
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
            val vm = viewModel()
            advanceUntilIdle()

            assertIs<TvSignInUiState.SignedOut>(vm.uiState.value)
        }

    @Test
    fun starts_signed_in_when_credentials_already_stored() =
        runTest(dispatcher) {
            val vm = viewModel(manager = authManager(tokens()))
            advanceUntilIdle()

            assertIs<TvSignInUiState.SignedIn>(vm.uiState.value)
        }

    @Test
    fun reports_not_configured_when_credentials_missing() =
        runTest(dispatcher) {
            val vm = viewModel(isConfigured = false)
            advanceUntilIdle()

            assertEquals(TvSignInUiState.NotConfigured, vm.uiState.value)
        }

    @Test
    fun sign_in_shows_the_code_while_waiting_for_approval() =
        runTest(dispatcher) {
            val vm = viewModel(tokenResponses = arrayOf(PENDING))
            advanceUntilIdle()

            vm.signIn()
            // Not advanceUntilIdle(): the real (mocked) HTTP call inside requestCode()
            // resumes on a dispatcher outside this test's virtual clock, so nothing
            // guarantees it has completed the instant advanceUntilIdle() returns.
            // Collecting the state directly waits for the real update instead of racing
            // it, and stops as soon as the code first appears -- exactly where a real
            // user would be staring at the screen, before any polling has a chance to
            // rush past it.
            val state = vm.uiState.first { it is TvSignInUiState.AwaitingApproval } as TvSignInUiState.AwaitingApproval

            assertEquals("ABCD-EFGH", state.userCode)
            assertEquals("https://www.google.com/device", state.verificationUrl)
        }

    @Test
    fun approval_persists_tokens_and_reports_signed_in() =
        runTest(dispatcher) {
            val manager = authManager()
            val vm = viewModel(manager = manager, tokenResponses = arrayOf(PENDING, AUTHORIZED))
            advanceUntilIdle()

            vm.signIn()
            vm.uiState.first { it is TvSignInUiState.SignedIn }

            assertEquals("at", manager.accessToken())
        }

    @Test
    fun declined_consent_surfaces_a_message_rather_than_hanging() =
        runTest(dispatcher) {
            val vm = viewModel(tokenResponses = arrayOf(DENIED))
            advanceUntilIdle()

            vm.signIn()
            // Matched on having a message, not just the type: the pre-sign-in state is
            // also SignedOut (with no message), and that stale value is what a bare
            // `first { it is SignedOut }` would grab before signIn()'s coroutine has even
            // run.
            val state = vm.uiState.first { it is TvSignInUiState.SignedOut && it.message != null }
            assertNotNull((state as TvSignInUiState.SignedOut).message)
        }

    @Test
    fun authorization_without_a_refresh_token_is_treated_as_failure() =
        runTest(dispatcher) {
            // Same rule as the phone app: without a refresh token the session silently
            // dies at the first expiry, so this must not look like a success.
            val manager = authManager()
            val vm = viewModel(manager = manager, tokenResponses = arrayOf(AUTHORIZED_NO_REFRESH_TOKEN))
            advanceUntilIdle()

            vm.signIn()
            val state = vm.uiState.first { it is TvSignInUiState.SignedOut && it.message != null }

            assertNotNull((state as TvSignInUiState.SignedOut).message)
            assertFalse(manager.isSignedIn())
        }

    @Test
    fun sign_out_clears_the_session() =
        runTest(dispatcher) {
            val manager = authManager(tokens())
            val vm = viewModel(manager = manager)
            advanceUntilIdle()

            vm.signOut()
            advanceUntilIdle()

            assertIs<TvSignInUiState.SignedOut>(vm.uiState.value)
            assertFalse(manager.isSignedIn())
        }
}
