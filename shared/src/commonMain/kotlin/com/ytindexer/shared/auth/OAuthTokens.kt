package com.ytindexer.shared.auth

/**
 * OAuth credentials for the signed-in Google account.
 *
 * @param accessToken short-lived bearer token sent to the YouTube Data API.
 * @param refreshToken long-lived token used to mint new access tokens. Google only
 *   returns this on the *first* authorization for a given grant, so a refresh response
 *   that omits it must not wipe the one we already hold.
 * @param expiresAtEpochSeconds absolute expiry, not a duration, so it stays correct
 *   across process death and app restarts.
 * @param scopes granted scopes, as returned by Google. May be narrower than requested
 *   if the user unticked something on the consent screen.
 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
    val scopes: Set<String> = emptySet(),
) {
    /**
     * True when the access token is expired, or close enough that a request started now
     * could plausibly arrive after expiry.
     */
    fun isExpired(
        nowEpochSeconds: Long,
        leewaySeconds: Long = DEFAULT_LEEWAY_SECONDS,
    ): Boolean = nowEpochSeconds >= expiresAtEpochSeconds - leewaySeconds

    fun hasScope(scope: String): Boolean = scope in scopes

    companion object {
        /**
         * Refresh a little before actual expiry so a token doesn't die in flight on a
         * slow connection.
         */
        const val DEFAULT_LEEWAY_SECONDS: Long = 60
    }
}
