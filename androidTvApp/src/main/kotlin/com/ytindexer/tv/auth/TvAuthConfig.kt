package com.ytindexer.tv.auth

import com.ytindexer.tv.BuildConfig

/**
 * OAuth client settings for the TV app's device-code sign-in.
 *
 * A separate Google Cloud OAuth client from the phone app's: this grant requires the
 * "TV and Limited Input devices" client type, which -- unlike the phone app's "Android"
 * client type -- is a confidential client and issues a secret alongside the ID. See the
 * README for how to create one and where to put it.
 */
object TvAuthConfig {
    const val YOUTUBE_READONLY_SCOPE: String = "https://www.googleapis.com/auth/youtube.readonly"

    val requestedScopes: List<String> = listOf(YOUTUBE_READONLY_SCOPE)

    val clientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID_TV
    val clientSecret: String = BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET_TV

    /** True when `local.properties` supplied both a client ID and secret (see README). */
    val isConfigured: Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()
}
