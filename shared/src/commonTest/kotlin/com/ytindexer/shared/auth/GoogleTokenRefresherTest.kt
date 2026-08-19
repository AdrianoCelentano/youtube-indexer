package com.ytindexer.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

private fun clientReturning(
    status: HttpStatusCode,
    body: String,
): HttpClient =
    HttpClient(
        MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

private fun refresher(client: HttpClient) =
    GoogleTokenRefresher(client, clientId = "test-client-id", clock = Clock { NOW })

class GoogleTokenRefresherTest {
    @Test
    fun parses_successful_refresh_and_converts_expiry_to_absolute_time() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.OK,
                    """
                    {
                      "access_token": "new-access",
                      "expires_in": 3599,
                      "scope": "https://www.googleapis.com/auth/youtube.readonly",
                      "token_type": "Bearer"
                    }
                    """.trimIndent(),
                )

            val tokens = refresher(client).refresh("refresh-1")

            assertEquals("new-access", tokens.accessToken)
            assertEquals(NOW + 3599, tokens.expiresAtEpochSeconds)
            assertTrue(tokens.hasScope("https://www.googleapis.com/auth/youtube.readonly"))
        }

    @Test
    fun returns_null_refresh_token_when_google_omits_it() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.OK,
                    """{"access_token":"new-access","expires_in":3599}""",
                )

            // AuthManager relies on null here to mean "keep the existing refresh token".
            assertNull(refresher(client).refresh("refresh-1").refreshToken)
        }

    @Test
    fun maps_invalid_grant_to_refresh_rejected() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.BadRequest,
                    """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
                )

            val error =
                assertFailsWith<AuthError.RefreshRejected> { refresher(client).refresh("dead") }
            assertEquals("invalid_grant", error.oauthError)
        }

    @Test
    fun other_oauth_errors_are_not_treated_as_a_dead_grant() =
        runTest {
            // A misconfigured client must not silently sign the user out.
            val client =
                clientReturning(
                    HttpStatusCode.Unauthorized,
                    """{"error":"invalid_client"}""",
                )

            assertFailsWith<AuthError.Unexpected> { refresher(client).refresh("refresh-1") }
        }

    @Test
    fun server_error_maps_to_unexpected_rather_than_rejecting_the_grant() =
        runTest {
            val client =
                HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }

            assertFailsWith<AuthError.Unexpected> { refresher(client).refresh("refresh-1") }
        }
}
