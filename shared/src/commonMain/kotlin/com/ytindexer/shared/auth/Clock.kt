package com.ytindexer.shared.auth

/**
 * Wall-clock seconds since the Unix epoch.
 *
 * Injected rather than called statically so expiry logic is testable without sleeping.
 */
fun interface Clock {
    fun nowEpochSeconds(): Long

    companion object {
        val System: Clock = Clock { currentTimeMillis() / MILLIS_PER_SECOND }

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/** Platform wall-clock time in milliseconds. */
internal expect fun currentTimeMillis(): Long
