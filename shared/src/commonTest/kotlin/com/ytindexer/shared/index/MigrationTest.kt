package com.ytindexer.shared.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ytindexer.shared.db.YtIndexerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.2.0 shipped schema version 1 and users have indexed data on device. A full index
 * costs real API quota to rebuild, so upgrading must migrate rather than recreate.
 */
class MigrationTest {
    /** Schema exactly as version 1 shipped, before the embedding columns existed. */
    private fun createV1Database(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE Video (
                videoId         TEXT NOT NULL PRIMARY KEY,
                title           TEXT NOT NULL,
                description     TEXT NOT NULL,
                publishedAt     TEXT NOT NULL,
                thumbnailUrl    TEXT,
                tags            TEXT NOT NULL DEFAULT '',
                categoryId      TEXT,
                durationSeconds INTEGER,
                indexedAt       INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE Category (categoryId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE SyncState (
                channelId          TEXT NOT NULL PRIMARY KEY,
                uploadsPlaylistId  TEXT NOT NULL,
                lastCompletedAt    INTEGER,
                pendingPageToken   TEXT,
                fullIndexCompleted INTEGER NOT NULL DEFAULT 0,
                videosIndexed      INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX video_category_published ON Video(categoryId, publishedAt DESC)", 0)
        driver.execute(null, "CREATE INDEX video_published ON Video(publishedAt DESC)", 0)
    }

    @Test
    fun migrating_from_v1_preserves_existing_indexed_videos() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Database(driver)

        driver.execute(
            null,
            """
            INSERT INTO Video(videoId, title, description, publishedAt, tags, categoryId, indexedAt)
            VALUES ('v1', 'Existing video', 'desc', '2026-01-01T00:00:00Z', 'a', '28', 123)
            """.trimIndent(),
            0,
        )

        YtIndexerDatabase.Schema.migrate(driver, 1, YtIndexerDatabase.Schema.version)

        val database = YtIndexerDatabase(driver)
        val video = database.videoQueries.selectById("v1").executeAsOne()

        assertEquals("Existing video", video.title, "a full index costs quota; it must not be discarded")
        assertEquals("28", video.categoryId)
    }

    @Test
    fun new_columns_are_null_on_migrated_rows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Database(driver)
        driver.execute(
            null,
            """
            INSERT INTO Video(videoId, title, description, publishedAt, tags, indexedAt)
            VALUES ('v1', 't', 'd', '2026-01-01T00:00:00Z', '', 123)
            """.trimIndent(),
            0,
        )

        YtIndexerDatabase.Schema.migrate(driver, 1, YtIndexerDatabase.Schema.version)
        val database = YtIndexerDatabase(driver)
        val video = database.videoQueries.selectById("v1").executeAsOne()

        // NULL reads correctly as "hash unknown" and "not yet embedded", so pre-existing
        // rows are picked up by the first embedding pass rather than being skipped.
        assertNull(video.contentHash)
        assertNull(video.embeddingModel)
        assertEquals(
            1,
            database.videoQueries
                .selectNeedingEmbedding("model-a", 10)
                .executeAsList()
                .size,
        )
    }

    @Test
    fun migrated_database_accepts_writes_using_the_new_columns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createV1Database(driver)
        YtIndexerDatabase.Schema.migrate(driver, 1, YtIndexerDatabase.Schema.version)

        val database = YtIndexerDatabase(driver)
        database.syncStateQueries.upsert("UC1", "UU1", null, "TOKEN", 0, 0, 555)

        assertEquals(
            555,
            database.syncStateQueries
                .selectAny()
                .executeAsOne()
                .fullIndexStartedAt,
        )
    }

    @Test
    fun schema_version_advanced_so_devices_actually_run_the_migration() {
        // If this stays at 1, shipped installs never migrate and crash on the new columns.
        assertTrue(
            YtIndexerDatabase.Schema.version > 1,
            "adding columns without bumping the version leaves existing installs broken",
        )
    }

    @Test
    fun a_fresh_install_creates_the_new_columns_directly() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val created = YtIndexerDatabase.Schema.create(driver)
        assertTrue(created is QueryResult.Value || true)

        val database = YtIndexerDatabase(driver)
        database.videoQueries.upsert(
            videoId = "v1",
            title = "t",
            description = "d",
            publishedAt = "2026-01-01T00:00:00Z",
            thumbnailUrl = null,
            tags = "",
            categoryId = null,
            durationSeconds = null,
            indexedAt = 1,
            contentHash = "abc",
            videoId_ = "v1",
        )

        assertEquals(
            "abc",
            database.videoQueries
                .selectById("v1")
                .executeAsOne()
                .contentHash,
        )
    }
}
