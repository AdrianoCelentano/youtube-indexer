package com.ytindexer.android.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ytindexer.shared.index.createDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Reproduces a fresh install: the database is created from the .sq schema rather than
 * reached by migration. Updating exercises the migration path instead, which is why a
 * create-only fault shows up for new users and not existing ones.
 *
 * Uses the real Android SQLite via Robolectric, not the JVM sqlite-jdbc driver the shared
 * tests use -- the two have different compile-time options, so a JVM-only test cannot
 * tell whether something works on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreshInstallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun database_can_be_created_and_queried_from_scratch() {
        val database = createDatabase(context)

        assertEquals(0L, database.videoQueries.countAll().executeAsOne())
        assertEquals(0L, database.videoSearchQueries.countAll().executeAsOne())
        assertEquals(0L, database.quotaUsageQueries.usedOn("2026-08-24").executeAsOne())
    }
}
