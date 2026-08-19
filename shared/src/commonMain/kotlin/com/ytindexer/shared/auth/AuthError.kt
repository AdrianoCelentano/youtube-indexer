package com.ytindexer.shared.auth

/**
 * Why an auth operation failed.
 *
 * The distinction that matters to callers is [RefreshRejected] versus everything else:
 * a rejected refresh means the grant is gone for good and the user must sign in again,
 * whereas [Network] is transient and worth retrying.
 */
sealed class AuthError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No credentials stored -- the user has never signed in, or signed out. */
    data object NotSignedIn : AuthError("No stored credentials; sign-in required")

    /**
     * Google refused the refresh token (`invalid_grant`): revoked by the user, expired
     * through inactivity, or invalidated by a password change. Unrecoverable -- the
     * stored credentials get cleared and the user has to re-authorize.
     */
    class RefreshRejected(
        val oauthError: String?,
        cause: Throwable? = null,
    ) : AuthError("Refresh token rejected by Google: ${oauthError ?: "unknown"}", cause)

    /** Transient transport failure. Retrying later is reasonable. */
    class Network(
        cause: Throwable?,
    ) : AuthError("Network failure during token refresh", cause)

    /** Google returned something we could not parse or did not expect. */
    class Unexpected(
        message: String,
        cause: Throwable? = null,
    ) : AuthError(message, cause)
}
