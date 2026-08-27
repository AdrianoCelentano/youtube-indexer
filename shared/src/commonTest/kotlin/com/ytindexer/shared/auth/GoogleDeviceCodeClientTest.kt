package com.ytindexer.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertIs
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

private fun deviceCodeClient(client: HttpClient) =
    GoogleDeviceCodeClient(client, clientId = "tv-client-id", clientSecret = "tv-secret", clock = Clock { NOW })

class GoogleDeviceCodeClientTest {
    @Test
    fun requestCode_converts_expiry_to_absolute_time() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.OK,
                    """
                    {
                      "device_code": "dc-1",
                      "user_code": "ABCD-EFGH",
                      "verification_url": "https://www.google.com/device",
                      "expires_in": 1800,
                      "interval": 5
                    }
                    """.trimIndent(),
                )

            val session = deviceCodeClient(client).requestCode(listOf("scope-a"))

            assertEquals("dc-1", session.deviceCode)
            assertEquals("ABCD-EFGH", session.userCode)
            assertEquals(NOW + 1800, session.expiresAtEpochSeconds)
            assertEquals(5L, session.pollIntervalSeconds)
        }

    @Test
    fun requestCode_defaults_interval_when_google_omits_it() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.OK,
                    """
                    {"device_code":"dc-1","user_code":"ABCD","verification_url":"https://x","expires_in":1800}
                    """.trimIndent(),
                )

            assertEquals(5L, deviceCodeClient(client).requestCode(listOf("scope-a")).pollIntervalSeconds)
        }

    @Test
    fun poll_returns_authorized_tokens_on_success() =
        runTest {
            val client =
                clientReturning(
                    HttpStatusCode.OK,
                    """{"access_token":"at","refresh_token":"rt","expires_in":3599,"scope":"scope-a"}""",
                )

            val result = deviceCodeClient(client).poll("dc-1")

            val authorized = assertIs<DeviceCodePollResult.Authorized>(result)
            assertEquals("at", authorized.tokens.accessToken)
            assertEquals("rt", authorized.tokens.refreshToken)
            assertTrue(authorized.tokens.hasScope("scope-a"))
        }

    @Test
    fun poll_maps_authorization_pending_to_pending() =
        runTest {
            val client = clientReturning(HttpStatusCode.BadRequest, """{"error":"authorization_pending"}""")
            assertEquals(DeviceCodePollResult.Pending, deviceCodeClient(client).poll("dc-1"))
        }

    @Test
    fun poll_maps_slow_down() =
        runTest {
            val client = clientReturning(HttpStatusCode.BadRequest, """{"error":"slow_down"}""")
            assertEquals(DeviceCodePollResult.SlowDown, deviceCodeClient(client).poll("dc-1"))
        }

    @Test
    fun poll_maps_access_denied() =
        runTest {
            val client = clientReturning(HttpStatusCode.BadRequest, """{"error":"access_denied"}""")
            assertEquals(DeviceCodePollResult.AccessDenied, deviceCodeClient(client).poll("dc-1"))
        }

    @Test
    fun poll_maps_expired_token() =
        runTest {
            val client = clientReturning(HttpStatusCode.BadRequest, """{"error":"expired_token"}""")
            assertEquals(DeviceCodePollResult.Expired, deviceCodeClient(client).poll("dc-1"))
        }

    @Test
    fun poll_throws_on_an_unrecognised_error() =
        runTest {
            val client = clientReturning(HttpStatusCode.BadRequest, """{"error":"invalid_client"}""")
            assertFailsWith<AuthError.Unexpected> { deviceCodeClient(client).poll("dc-1") }
        }
}
