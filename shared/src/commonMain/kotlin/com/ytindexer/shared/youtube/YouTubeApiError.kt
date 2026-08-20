package com.ytindexer.shared.youtube

/**
 * Failures from the YouTube Data API.
 *
 * [QuotaExceeded] is separated out because it is not retryable within the same day and
 * needs distinct UI: the daily quota resets at midnight Pacific, so a retry loop would
 * burn requests for hours to no effect.
 */
sealed class YouTubeApiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Daily quota is spent (HTTP 403, reason `quotaExceeded`/`dailyLimitExceeded`).
     * Resets at midnight Pacific time.
     */
    class QuotaExceeded(
        val reason: String?,
    ) : YouTubeApiError("YouTube API quota exceeded ($reason)")

    /** 403 for a reason other than quota, e.g. the account lacks a channel. */
    class Forbidden(
        val reason: String?,
    ) : YouTubeApiError("Forbidden by YouTube API ($reason)")

    /**
     * Credentials were rejected and refreshing did not help.
     *
     * Carries the underlying [AuthError][com.ytindexer.shared.auth.AuthError] when the
     * failure came from the token layer, so the original reason isn't lost.
     */
    class Unauthorized(
        cause: Throwable? = null,
    ) : YouTubeApiError("YouTube API rejected the credentials", cause)

    /** The requested resource does not exist. */
    class NotFound(
        message: String,
    ) : YouTubeApiError(message)

    /** Transient: network failure or 5xx. Worth retrying with backoff. */
    class Transient(
        message: String,
        cause: Throwable? = null,
    ) : YouTubeApiError(message, cause)

    class Unexpected(
        message: String,
        cause: Throwable? = null,
    ) : YouTubeApiError(message, cause)
}
