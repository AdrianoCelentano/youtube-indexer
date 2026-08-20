package com.ytindexer.shared.youtube

/**
 * Parses the ISO-8601 durations YouTube returns in `contentDetails.duration`.
 *
 * Only the subset YouTube actually emits is handled: `PT#H#M#S`, plus a leading day
 * component (`P#DT...`) which appears on very long livestream archives. Weeks, months and
 * years are not produced for video durations and are not supported.
 *
 * @return total seconds, or null if the value is absent or unparseable. Unparseable is
 *   deliberately not an error -- a video with an odd duration should still be indexed and
 *   searchable.
 */
internal fun parseIsoDurationSeconds(value: String?): Long? {
    val match = value?.let { ISO_DURATION.matchEntire(it) } ?: return null
    // Groups in order: days, hours, minutes, seconds.
    val parts = match.groupValues.drop(1)

    // "PT" with no components parses but means nothing as a duration.
    return if (parts.all(String::isEmpty)) {
        null
    } else {
        parts[INDEX_DAYS].toLongOrZero() * SECONDS_PER_DAY +
            parts[INDEX_HOURS].toLongOrZero() * SECONDS_PER_HOUR +
            parts[INDEX_MINUTES].toLongOrZero() * SECONDS_PER_MINUTE +
            parts[INDEX_SECONDS].toLongOrZero()
    }
}

private fun String.toLongOrZero(): Long = if (isEmpty()) 0L else toLongOrNull() ?: 0L

private const val INDEX_DAYS = 0
private const val INDEX_HOURS = 1
private const val INDEX_MINUTES = 2
private const val INDEX_SECONDS = 3

private val ISO_DURATION = Regex("""P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
