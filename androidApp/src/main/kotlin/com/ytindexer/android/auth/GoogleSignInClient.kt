package com.ytindexer.android.auth

import android.content.Context
import android.content.Intent
import com.ytindexer.shared.auth.AuthError
import com.ytindexer.shared.auth.OAuthTokens
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the browser-based authorization-code flow and turns its result into the
 * platform-agnostic [OAuthTokens] the shared `AuthManager` understands.
 *
 * AppAuth runs the flow in a Custom Tab, which keeps credentials out of the app's process
 * and lets the user reuse an existing Chrome Google session.
 *
 * Callers must [close] this when done -- `AuthorizationService` holds a browser binding.
 */
class GoogleSignInClient(
    context: Context,
    private val authService: AuthorizationService = AuthorizationService(context),
) : SignInFlow {
    /**
     * Intent that launches the consent screen. Hand this to an activity-result launcher;
     * feed the returned intent back into [handleResult].
     */
    override fun createSignInIntent(): Intent {
        check(GoogleAuthConfig.isConfigured) {
            "No Google OAuth client ID. Add googleOauthClientIdAndroid to local.properties (see README)."
        }

        val request =
            AuthorizationRequest
                .Builder(
                    GoogleAuthConfig.serviceConfig,
                    GoogleAuthConfig.clientId,
                    ResponseTypeValues.CODE,
                    GoogleAuthConfig.redirectUri,
                ).setScopes(GoogleAuthConfig.requestedScopes)
                .setPrompt(GoogleAuthConfig.PROMPT)
                .setAdditionalParameters(GoogleAuthConfig.authRequestParams)
                .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Completes sign-in: reads the authorization response and exchanges the code for
     * tokens.
     *
     * @param data the intent returned by the activity result, or null if the user backed
     *   out of the browser without deciding.
     * @throws AuthError.NotSignedIn if the user cancelled or denied consent.
     * @throws AuthError.Network / [AuthError.Unexpected] if the exchange failed.
     */
    override suspend fun handleResult(data: Intent?): OAuthTokens {
        if (data == null) throw AuthError.NotSignedIn

        val response = AuthorizationResponse.fromIntent(data)
        val failure = AuthorizationException.fromIntent(data)

        if (response == null) {
            // user_cancelled / access_denied both land here.
            throw failure?.toAuthError() ?: AuthError.NotSignedIn
        }

        val tokenResponse = exchangeCode(response)

        val accessToken =
            tokenResponse.accessToken
                ?: throw AuthError.Unexpected("Token exchange returned no access token")

        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = tokenResponse.refreshToken,
            expiresAtEpochSeconds =
                tokenResponse.accessTokenExpirationTime?.let { it / MILLIS_PER_SECOND }
                    ?: 0L,
            scopes = tokenResponse.scopeSet.orEmpty(),
        )
    }

    private suspend fun exchangeCode(response: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { continuation ->
            // Public client: no client authentication is attached to the exchange, the
            // PKCE verifier AppAuth generated is what proves this is the same app.
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokens, exception ->
                when {
                    tokens != null -> {
                        continuation.resume(tokens)
                    }

                    else -> {
                        continuation.resumeWithException(
                            exception?.toAuthError()
                                ?: AuthError.Unexpected("Token exchange failed with no error detail"),
                        )
                    }
                }
            }
        }

    override fun close() = authService.dispose()

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Maps AppAuth's error taxonomy onto the shared [AuthError] types. */
internal fun AuthorizationException.toAuthError(): AuthError =
    when {
        type == AuthorizationException.TYPE_GENERAL_ERROR &&
            code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code -> {
            AuthError.Network(this)
        }

        this == AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED ||
            this == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW -> {
            AuthError.NotSignedIn
        }

        else -> {
            AuthError.Unexpected(errorDescription ?: error ?: "Authorization failed", this)
        }
    }
