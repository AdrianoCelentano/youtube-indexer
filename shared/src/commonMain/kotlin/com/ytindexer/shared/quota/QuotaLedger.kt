package com.ytindexer.shared.quota

import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.db.YtIndexerDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Tracks how much daily API quota has been spent.
 *
 * Persisted rather than in-memory: an in-memory counter resets on every app restart,
 * which would let a caption backfill spend the daily allowance several times over and
 * leave ordinary indexing failing with `quotaExceeded` for the rest of the day.
 *
 * Buckets are keyed by **Pacific calendar day**, because that is when Google resets the
 * allowance. A fixed UTC-8 offset would be an hour wrong for half the year, which around
 * the boundary means either spending tomorrow's budget early or idling for an hour.
 */
class QuotaLedger(
    private val database: YtIndexerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val dailyLimit: Int = QuotaCost.DEFAULT_DAILY_LIMIT,
) {
    /** Units already spent today. */
    suspend fun usedToday(): Long =
        withContext(ioDispatcher) {
            database.quotaUsageQueries
                .usedOn(currentPacificDay())
                .executeAsOne()
        }

    suspend fun remainingToday(): Long = (dailyLimit - usedToday()).coerceAtLeast(0)

    /**
     * Whether [units] can be spent while leaving [reserve] untouched.
     *
     * @param reserve units to keep back for other work. Caption backfill passes
     *   [QuotaCost.RESERVED_FOR_INDEXING] so an optional feature cannot starve indexing.
     */
    suspend fun canSpend(
        units: Int,
        reserve: Int = 0,
    ): Boolean = remainingToday() - reserve >= units

    /** Records spend. Call after a request is actually issued -- failed calls cost quota too. */
    suspend fun record(units: Int) =
        withContext(ioDispatcher) {
            val day = currentPacificDay()
            database.quotaUsageQueries.recordUsage(day, day, units.toLong())
        }

    /** How many transcripts today's remaining budget allows, respecting the reserve. */
    suspend fun transcriptsAffordableToday(): Int {
        val spendable = remainingToday() - QuotaCost.RESERVED_FOR_INDEXING
        if (spendable <= 0) return 0
        return (spendable / QuotaCost.TRANSCRIPT_PER_VIDEO).toInt()
    }

    /** Drops buckets from previous days; only today's is ever read. */
    suspend fun pruneOldDays() =
        withContext(ioDispatcher) {
            database.quotaUsageQueries.deleteOlderThan(currentPacificDay())
        }

    internal fun currentPacificDay(): String =
        Instant
            .fromEpochSeconds(clock.nowEpochSeconds())
            .toLocalDateTime(PACIFIC)
            .date
            .toString()

    private companion object {
        /** Google resets YouTube Data API quota at midnight Pacific. */
        val PACIFIC = TimeZone.of("America/Los_Angeles")
    }
}
