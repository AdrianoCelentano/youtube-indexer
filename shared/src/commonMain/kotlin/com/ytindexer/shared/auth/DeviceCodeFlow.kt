package com.ytindexer.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * A code and URL pair the user enters on a second device (phone, laptop) to authorize
 * this one.
 *
 * @param verificationUrl where the user types [userCode]. Short and readable at couch
 *   distance -- the whole point of this flow on a TV.
 * @param expiresAtEpochSeconds after this, [userCode] is dead and a fresh one must be
 *   requested with [GoogleDeviceCodeClient.requestCode].
 * @param pollIntervalSeconds minimum gap Google asked for between polls. Polling faster
 *   gets [DeviceCodePollResult.SlowDown] rather than a faster answer.
 */
data class DeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtEpochSeconds: Long,
    val pollIntervalSeconds: Long,
)

/** Where a call to [GoogleDeviceCodeClient.poll] landed. */
sealed class DeviceCodePollResult {
    data class Authorized(
        val tokens: OAuthTokens,
    ) : DeviceCodePollResult()

    /** Normal and expected: the user has not finished entering the code yet. Keep polling. */
    data object Pending : DeviceCodePollResult()

    /** Google asked for a longer gap between polls; the caller should widen its interval. */
    data object SlowDown : DeviceCodePollResult()

    /** The user declined consent on the verification page. */
    data object AccessDenied : DeviceCodePollResult()

    /** [DeviceCodeSession.userCode] expired before the user finished. Request a new one. */
    data object Expired : DeviceCodePollResult()
}

/**
 * OAuth 2.0 for TV and Limited-Input Device Applications:
 * https://developers.google.com/identity/protocols/oauth2/limited-input-device
 *
 * Used by the TV app instead of the phone app's AppAuth browser-redirect flow, because
 * Android TV boxes generally have no browser capable of hosting a Custom Tab. Instead the
 * user is shown a short code and a URL, types it in on a phone or laptop, and the TV
 * polls [poll] until that finishes.
 *
 * Owning the retry loop (the delay between polls, widening it on [DeviceCodePollResult.SlowDown],
 * stopping at [DeviceCodeSession.expiresAtEpochSeconds]) is deliberately left to the
 * caller: how the wait is shown -- a countdown, a spinner, nothing at all -- is a UI
 * concern this class should not know about. See `TvSignInViewModel`.
 */
class GoogleDeviceCodeClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock,
    private val deviceCodeEndpoint: String = GOOGLE_DEVICE_CODE_ENDPOINT,
    private val tokenEndpoint: String = GOOGLE_TOKEN_ENDPOINT,
) {
    suspend fun requestCode(scopes: List<String>): DeviceCodeSession {
        val response: HttpResponse =
            try {
                httpClient.submitForm(
                    url = deviceCodeEndpoint,
                    formParameters =
                        Parameters.build {
                            append("client_id", clientId)
                            append("scope", scopes.joinToString(" "))
                        },
                )
            } catch (e: IOException) {
                throw AuthError.Network(e)
            }

        if (!response.status.isSuccess()) {
            throw AuthError.Unexpected("Device code request failed: HTTP ${response.status.value}")
        }

        val body = response.parseBody<DeviceCodeResponse>("device code response")

        return DeviceCodeSession(
            deviceCode = body.deviceCode,
            userCode = body.userCode,
            verificationUrl = body.verificationUrl,
            expiresAtEpochSeconds = clock.nowEpochSeconds() + body.expiresInSeconds,
            pollIntervalSeconds = body.intervalSeconds,
        )
    }

    /** One poll of the token endpoint for [deviceCode]. */
    suspend fun poll(deviceCode: String): DeviceCodePollResult {
        val response: HttpResponse =
            try {
                httpClient.submitForm(
                    url = tokenEndpoint,
                    formParameters =
                        Parameters.build {
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                            append("device_code", deviceCode)
                            append("grant_type", DEVICE_CODE_GRANT_TYPE)
                        },
                )
            } catch (e: IOException) {
                throw AuthError.Network(e)
            }

        if (response.status.isSuccess()) {
            val body = response.parseBody<TokenResponse>("token response")

            return DeviceCodePollResult.Authorized(
                OAuthTokens(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresAtEpochSeconds = clock.nowEpochSeconds() + body.expiresInSeconds,
                    scopes =
                        body.scope
                            ?.split(" ")
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty(),
                ),
            )
        }

        // Google signals "still waiting" as an HTTP error with a specific `error` code
        // rather than a distinct status, so the non-2xx branch has to distinguish
        // "keep polling" from "actually failed" itself.
        return when (runCatching { response.body<TokenErrorResponse>().error }.getOrNull()) {
            AUTHORIZATION_PENDING -> DeviceCodePollResult.Pending
            SLOW_DOWN -> DeviceCodePollResult.SlowDown
            ACCESS_DENIED -> DeviceCodePollResult.AccessDenied
            EXPIRED_TOKEN -> DeviceCodePollResult.Expired
            else -> throw AuthError.Unexpected("Device token poll failed: HTTP ${response.status.value}")
        }
    }

    private companion object {
        const val GOOGLE_DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        const val AUTHORIZATION_PENDING = "authorization_pending"
        const val SLOW_DOWN = "slow_down"
        const val ACCESS_DENIED = "access_denied"
        const val EXPIRED_TOKEN = "expired_token"
    }
}

@Serializable
internal data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("interval") val intervalSeconds: Long = DEFAULT_POLL_INTERVAL_SECONDS,
)

private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L

/** Shared by [GoogleDeviceCodeClient.requestCode] and [GoogleDeviceCodeClient.poll]. */
private suspend inline fun <reified T> HttpResponse.parseBody(description: String): T =
    try {
        body<T>()
    } catch (e: SerializationException) {
        throw AuthError.Unexpected("Could not parse $description", e)
    } catch (e: NoTransformationFoundException) {
        throw AuthError.Unexpected("$description had an unexpected content type", e)
    }
