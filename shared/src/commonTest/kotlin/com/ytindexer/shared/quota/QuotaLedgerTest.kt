package com.ytindexer.shared.quota

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.db.YtIndexerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-08-25 12:00 UTC == 2026-08-25 05:00 Pacific. */
private const val MIDDAY_UTC = 1_787_659_200L

/**
 * 2026-08-25 02:00 UTC == 2026-08-24 19:00 Pacific.
 *
 * Chosen because UTC and Pacific disagree about the date here: UTC says the 25th,
 * Pacific says the 24th. An instant where both agree cannot tell the two bucketings
 * apart, which is exactly how the first version of this test managed to pass while the
 * implementation used UTC.
 */
private const val EVENING_BEFORE_PACIFIC = 1_787_623_200L

private fun database(): YtIndexerDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    YtIndexerDatabase.Schema.create(driver)
    return YtIndexerDatabase(driver)
}

private fun ledger(
    db: YtIndexerDatabase = database(),
    now: Long = MIDDAY_UTC,
) = QuotaLedger(db, Dispatchers.Unconfined, Clock { now })

class QuotaLedgerTest {
    @Test
    fun starts_at_zero_used() =
        runTest {
            assertEquals(0L, ledger().usedToday())
        }

    @Test
    fun records_and_accumulates_spend() =
        runTest {
            val ledger = ledger()
            ledger.record(QuotaCost.CAPTIONS_LIST)
            ledger.record(QuotaCost.CAPTIONS_DOWNLOAD)

            assertEquals(250L, ledger.usedToday())
            assertEquals(9_750L, ledger.remainingToday())
        }

    @Test
    fun spend_survives_a_new_ledger_instance() =
        runTest {
            // The budget must outlive process death, or an app restart would let the
            // backfill spend the daily allowance several times over.
            val db = database()
            ledger(db).record(5_000)

            assertEquals(5_000L, ledger(db).usedToday())
        }

    @Test
    fun the_reserve_is_not_spendable() =
        runTest {
            val db = database()
            val ledger = ledger(db)
            // Leaves 2,100 units: above the 2,000 reserve by only 100.
            ledger.record(7_900)

            assertTrue(ledger.canSpend(100, reserve = QuotaCost.RESERVED_FOR_INDEXING))
            assertFalse(
                ledger.canSpend(200, reserve = QuotaCost.RESERVED_FOR_INDEXING),
                "the reserve exists so an optional backfill cannot starve ordinary indexing",
            )
        }

    @Test
    fun affordable_transcripts_respects_the_reserve() =
        runTest {
            val ledger = ledger()

            // (10,000 - 2,000 reserve) / 250 per transcript = 32.
            assertEquals(32, ledger.transcriptsAffordableToday())
        }

    @Test
    fun no_transcripts_are_affordable_once_only_the_reserve_remains() =
        runTest {
            val db = database()
            val ledger = ledger(db)
            ledger.record(8_000)

            assertEquals(0, ledger.transcriptsAffordableToday())
        }

    @Test
    fun budget_resets_on_the_next_pacific_day() =
        runTest {
            val db = database()
            ledger(db, now = MIDDAY_UTC).record(9_000)

            // Same instant plus one day.
            val tomorrow = ledger(db, now = MIDDAY_UTC + 86_400)

            assertEquals(0L, tomorrow.usedToday())
            assertEquals(10_000L, tomorrow.remainingToday())
        }

    @Test
    fun day_boundary_follows_pacific_not_utc() =
        runTest {
            val db = database()

            // Spent on the Pacific 24th (which UTC calls the 25th).
            ledger(db, now = EVENING_BEFORE_PACIFIC).record(100)

            // Read on the Pacific 25th: a new quota day, so nothing is spent yet.
            val nextPacificDay = ledger(db, now = MIDDAY_UTC)

            assertEquals(
                0L,
                nextPacificDay.usedToday(),
                "UTC bucketing would call both instants the 25th and wrongly report 100 spent, " +
                    "carrying yesterday's spend into today's budget",
            )
        }

    @Test
    fun spend_within_one_pacific_day_accumulates_across_the_utc_midnight() =
        runTest {
            val db = database()

            // 19:00 and 23:00 Pacific on the 24th straddle midnight UTC but are one
            // Pacific quota day, so they must share a bucket.
            ledger(db, now = EVENING_BEFORE_PACIFIC).record(100)
            ledger(db, now = EVENING_BEFORE_PACIFIC + (4 * 3_600)).record(50)

            assertEquals(150L, ledger(db, now = EVENING_BEFORE_PACIFIC).usedToday())
        }
}
