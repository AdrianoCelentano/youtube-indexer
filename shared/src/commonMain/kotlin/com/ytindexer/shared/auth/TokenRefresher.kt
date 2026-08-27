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

/** Exchanges a refresh token for a fresh access token. */
fun interface TokenRefresher {
    suspend fun refresh(refreshToken: String): OAuthTokens
}

/**
 * Refreshes against Google's OAuth 2.0 token endpoint.
 *
 * This is a **public client** (installed app) by default: it sends `client_id` but no
 * `client_secret`, which is why the authorization step must use PKCE. Shipping a client
 * secret in an APK would not be secret.
 *
 * @param clientSecret only set for the TV app. Google's device-code client type
 *   ([GoogleDeviceCodeClient]) is a confidential client and expects its secret on every
 *   token-endpoint call, refresh included -- PKCE is not an option for that grant type.
 */
class GoogleTokenRefresher(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clock: Clock,
    private val clientSecret: String? = null,
    private val tokenEndpoint: String = GOOGLE_TOKEN_ENDPOINT,
) : TokenRefresher {
    override suspend fun refresh(refreshToken: String): OAuthTokens {
        val response: HttpResponse =
            try {
                httpClient.submitForm(
                    url = tokenEndpoint,
                    formParameters =
                        Parameters.build {
                            append("client_id", clientId)
                            append("refresh_token", refreshToken)
                            append("grant_type", "refresh_token")
                            clientSecret?.let { append("client_secret", it) }
                        },
                )
            } catch (e: IOException) {
                throw AuthError.Network(e)
            }

        if (!response.status.isSuccess()) {
            val error =
                runCatching { response.body<TokenErrorResponse>().error }.getOrNull()
            // Google signals a dead grant with invalid_grant; anything else 4xx/5xx is
            // treated as unexpected rather than silently destroying the user's session.
            throw if (error == INVALID_GRANT) {
                AuthError.RefreshRejected(error)
            } else {
                AuthError.Unexpected("Token refresh failed: HTTP ${response.status.value} ($error)")
            }
        }

        val body =
            try {
                response.body<TokenResponse>()
            } catch (e: SerializationException) {
                throw AuthError.Unexpected("Could not parse token response", e)
            } catch (e: NoTransformationFoundException) {
                throw AuthError.Unexpected("Token endpoint returned an unexpected content type", e)
            }

        return OAuthTokens(
            accessToken = body.accessToken,
            // Google omits refresh_token on refresh responses. Returning null here lets
            // AuthManager keep the existing one rather than wiping it.
            refreshToken = body.refreshToken,
            expiresAtEpochSeconds = clock.nowEpochSeconds() + body.expiresInSeconds,
            scopes = parseScopes(body.scope),
        )
    }

    /** Google returns granted scopes as a single space-delimited string. */
    private fun parseScopes(scope: String?): Set<String> {
        if (scope.isNullOrBlank()) return emptySet()
        return scope.split(" ").filter { it.isNotBlank() }.toSet()
    }

    companion object {
        const val GOOGLE_TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
        private const val INVALID_GRANT = "invalid_grant"
    }
}

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
)

@Serializable
internal data class TokenErrorResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
