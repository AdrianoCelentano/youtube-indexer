package com.ytindexer.android.auth

import android.net.Uri
import com.ytindexer.android.BuildConfig
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * OAuth endpoints and client settings for the phone app.
 *
 * This is a **public client**: PKCE, no client secret. AppAuth generates and verifies the
 * PKCE challenge automatically for an authorization-code request, so there is nothing to
 * configure here beyond the client ID and redirect.
 */
object GoogleAuthConfig {
    const val YOUTUBE_READONLY_SCOPE: String = "https://www.googleapis.com/auth/youtube.readonly"

    val clientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID

    /** True when `local.properties` supplied a client ID (see README). */
    val isConfigured: Boolean = clientId.isNotBlank()

    /**
     * Google's redirect for an Android OAuth client: the client ID reversed into a custom
     * scheme. Must match the `appAuthRedirectScheme` manifest placeholder, or Google
     * rejects the request with `redirect_uri_mismatch`.
     */
    val redirectUri: Uri = Uri.parse("${BuildConfig.APPAUTH_REDIRECT_SCHEME}:/oauth2redirect")

    val serviceConfig: AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token"),
        )

    /**
     * `access_type=offline` is what makes Google issue a refresh token at all.
     *
     * `prompt=consent` forces the consent screen every time. Without it Google only
     * returns a refresh token on the *first* authorization for a given client+account,
     * so a user who reinstalls the app would sign in successfully and then lose the
     * session at the first token expiry with no obvious cause.
     */
    val authRequestParams: Map<String, String> =
        mapOf(
            "access_type" to "offline",
            "prompt" to "consent",
        )
}
