package com.ytindexer.shared.index

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.auth.InMemoryTokenStore
import com.ytindexer.shared.auth.OAuthTokens
import com.ytindexer.shared.auth.TokenRefresher
import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.youtube.YouTubeApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

private fun inMemoryDatabase(): YtIndexerDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    YtIndexerDatabase.Schema.create(driver)
    return YtIndexerDatabase(driver)
}

/** Serves canned YouTube responses keyed by the endpoint being called. */
private class FakeYouTube {
    var quotaExhaustedAfterPages: Int? = null
    var failWith: HttpStatusCode? = null

    /** pageToken (null = first page) -> ids on that page, and the token for the next. */
    val pagesByToken = mutableMapOf<String?, Pair<List<String>, String?>>()
    var playlistCallCount = 0
        private set
    var videosCallCount = 0
        private set

    /** Every pageToken the client actually asked for, in order. */
    val requestedTokens = mutableListOf<String?>()

    /** Every video id the client actually fetched details for. */
    val requestedVideoIds = mutableListOf<String>()

    private fun playlistJson(token: String?): String {
        requestedTokens += token
        val (ids, next) = pagesByToken[token] ?: error("fake has no page for token=$token")
        playlistCallCount++
        val items = ids.joinToString(",") { """{"contentDetails":{"videoId":"$it"}}""" }
        val nextToken = next?.let { ""","nextPageToken":"$it"""" } ?: ""
        return """{"items":[$items]$nextToken}"""
    }

    private fun videosJson(idParam: String): String {
        val ids = idParam.split(",").filter { it.isNotBlank() }
        requestedVideoIds += ids
        val items =
            ids.joinToString(",") {
                """{"id":"$it","snippet":{"title":"T-$it","description":"D-$it",
                   "publishedAt":"2026-01-01T00:00:00Z","categoryId":"28"}}"""
            }
        return """{"items":[$items]}"""
    }

    fun client(): HttpClient =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json =
                    when {
                        "channels" in path -> {
                            """{"items":[{"id":"UC1","snippet":{"title":"Chan"},
                               "contentDetails":{"relatedPlaylists":{"uploads":"UU1"}}}]}"""
                        }

                        "playlistItems" in path -> {
                            val quota = quotaExhaustedAfterPages
                            if (quota != null && playlistCallCount >= quota) {
                                return@MockEngine respond(
                                    """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}""",
                                    HttpStatusCode.Forbidden,
                                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                                )
                            }
                            playlistJson(request.url.parameters["pageToken"])
                        }

                        "videos" in path -> {
                            failWith?.let {
                                return@MockEngine respond(
                                    """{"error":{"code":${it.value}}}""",
                                    it,
                                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                                )
                            }
                            videosCallCount++
                            videosJson(request.url.parameters["id"].orEmpty())
                        }

                        else -> {
                            """{"items":[]}"""
                        }
                    }

                respond(
                    json,
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}

private fun authManager() =
    AuthManager(
        tokenStore = InMemoryTokenStore(OAuthTokens("access", "refresh", NOW + 3600)),
        refresher = TokenRefresher { error("unused") },
        clock = Clock { NOW },
    )

class VideoIndexerTest {
    private fun indexer(
        fake: FakeYouTube,
        db: YtIndexerDatabase,
    ): Pair<VideoIndexer, VideoIndexStore> {
        val store = VideoIndexStore(db, Dispatchers.Unconfined)
        val api = YouTubeApiClient(fake.client(), authManager())
        return VideoIndexer(api, store, db, Dispatchers.Unconfined, Clock { NOW }) to store
    }

    @Test
    fun indexes_every_page_of_the_uploads_playlist() =
        runTest {
            val fake =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1", "v2") to "P2"
                    pagesByToken["P2"] = listOf("v3") to null
                }
            val db = inMemoryDatabase()
            val (indexer, store) = indexer(fake, db)

            val outcome = indexer.indexChannel()

            assertIs<IndexOutcome.Completed>(outcome)
            assertEquals(3, outcome.videosIndexed)
            assertEquals(3L, store.videoCount())
        }

    @Test
    fun stores_video_fields_and_round_trips_them() =
        runTest {
            val fake = FakeYouTube().apply { pagesByToken[null] = listOf("v1") to null }
            val db = inMemoryDatabase()
            val (indexer, store) = indexer(fake, db)

            indexer.indexChannel()
            val video = store.recentVideos(limit = 10).single()

            assertEquals("v1", video.id)
            assertEquals("T-v1", video.title)
            assertEquals("D-v1", video.description)
            assertEquals("28", video.categoryId)
        }

    @Test
    fun reports_progress_per_page() =
        runTest {
            val fake =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1") to "P2"
                    pagesByToken["P2"] = listOf("v2") to null
                }
            val (indexer, _) = indexer(fake, inMemoryDatabase())

            val seen = mutableListOf<IndexProgress>()
            indexer.indexChannel { seen += it }

            assertEquals(2, seen.size)
            assertEquals(listOf(false, true), seen.map { it.complete })
            assertEquals(listOf(1, 2), seen.map { it.videosIndexed })
        }

    // --- resumability ------------------------------------------------------------

    @Test
    fun quota_exhaustion_saves_the_cursor_instead_of_losing_progress() =
        runTest {
            val fake =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1") to "P2"
                    pagesByToken["P2"] = listOf("v2") to null
                    quotaExhaustedAfterPages = 1
                }
            val db = inMemoryDatabase()
            val (indexer, store) = indexer(fake, db)

            val outcome = indexer.indexChannel()

            assertIs<IndexOutcome.QuotaExhausted>(outcome)
            assertEquals(1, outcome.videosIndexed)
            assertEquals(1L, store.videoCount(), "the first page must survive")

            val state = db.syncStateQueries.selectAny().executeAsOne()
            assertEquals("P2", state.pendingPageToken, "next run must resume, not restart")
            assertEquals(0L, state.fullIndexCompleted)
        }

    @Test
    fun a_second_run_resumes_from_the_saved_cursor() =
        runTest {
            val db = inMemoryDatabase()

            // First run: quota dies after one page.
            val first =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1") to "P2"
                    pagesByToken["P2"] = listOf("v2") to null
                    quotaExhaustedAfterPages = 1
                }
            indexer(first, db).first.indexChannel()

            // Second run: fresh quota, and BOTH pages available. If the indexer ignored
            // the stored cursor it would happily restart from page one -- so the assertions
            // below check which token was requested, not just the final count.
            val second =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1") to "P2"
                    pagesByToken["P2"] = listOf("v2") to null
                }
            val (indexer2, store2) = indexer(second, db)
            val outcome = indexer2.indexChannel()

            assertIs<IndexOutcome.Completed>(outcome)
            assertEquals(2L, store2.videoCount(), "both pages present, nothing lost or duplicated")
            assertEquals(
                listOf<String?>("P2"),
                second.requestedTokens,
                "must resume at the saved cursor, not restart from the first page",
            )
            assertEquals(
                listOf("v2"),
                second.requestedVideoIds,
                "re-fetching page one would waste quota on videos already indexed",
            )

            val state = db.syncStateQueries.selectAny().executeAsOne()
            assertEquals(1L, state.fullIndexCompleted)
            assertNull(state.pendingPageToken, "a finished index keeps no cursor")
        }

    @Test
    fun transient_failure_is_reported_and_progress_is_kept() =
        runTest {
            val fake =
                FakeYouTube().apply {
                    pagesByToken[null] = listOf("v1") to "P2"
                    failWith = HttpStatusCode.InternalServerError
                }
            val db = inMemoryDatabase()
            val (indexer, store) = indexer(fake, db)

            val outcome = indexer.indexChannel()

            assertIs<IndexOutcome.Interrupted>(outcome)
            assertEquals(0L, store.videoCount())
        }

    @Test
    fun re_indexing_the_same_videos_updates_rather_than_duplicating() =
        runTest {
            val db = inMemoryDatabase()
            val pages = { FakeYouTube().apply { pagesByToken[null] = listOf("v1", "v2") to null } }

            indexer(pages(), db).first.indexChannel()
            indexer(pages(), db).first.indexChannel()

            assertEquals(2L, VideoIndexStore(db, Dispatchers.Unconfined).videoCount())
        }

    // --- store behaviour ---------------------------------------------------------

    @Test
    fun tags_survive_a_round_trip_including_commas() =
        runTest {
            // A comma separator would corrupt these; the store joins on newlines.
            val db = inMemoryDatabase()
            val store = VideoIndexStore(db, Dispatchers.Unconfined)
            val video =
                com.ytindexer.shared.youtube.YouTubeVideo(
                    id = "v1",
                    title = "t",
                    description = "d",
                    publishedAt = "2026-01-01T00:00:00Z",
                    thumbnailUrl = null,
                    tags = listOf("kotlin, android", "multiplatform"),
                    categoryId = "28",
                    durationSeconds = 42,
                )

            store.upsertAll(listOf(video), NOW)

            assertEquals(
                listOf("kotlin, android", "multiplatform"),
                store.recentVideos(limit = 1).single().tags,
            )
        }

    @Test
    fun videos_with_no_tags_read_back_as_an_empty_list() =
        runTest {
            val store = VideoIndexStore(inMemoryDatabase(), Dispatchers.Unconfined)
            store.upsertAll(
                listOf(
                    com.ytindexer.shared.youtube.YouTubeVideo(
                        "v1",
                        "t",
                        "d",
                        "2026-01-01T00:00:00Z",
                        null,
                        emptyList(),
                        null,
                        null,
                    ),
                ),
                NOW,
            )

            assertEquals(emptyList(), store.recentVideos(limit = 1).single().tags)
        }

    @Test
    fun populated_categories_never_include_empty_ones() =
        runTest {
            val db = inMemoryDatabase()
            val store = VideoIndexStore(db, Dispatchers.Unconfined)
            store.upsertCategories(
                listOf(
                    com.ytindexer.shared.youtube
                        .VideoCategory("28", "Science & Technology"),
                    com.ytindexer.shared.youtube
                        .VideoCategory("10", "Music"),
                ),
            )
            store.upsertAll(
                listOf(
                    com.ytindexer.shared.youtube.YouTubeVideo(
                        "v1",
                        "t",
                        "d",
                        "2026-01-01T00:00:00Z",
                        null,
                        emptyList(),
                        "28",
                        null,
                    ),
                ),
                NOW,
            )

            val categories = store.populatedCategories()

            assertEquals(1, categories.size, "Music has no videos and must not be offered")
            assertEquals("Science & Technology", categories.single().title)
            assertEquals(1L, categories.single().videoCount)
        }

    @Test
    fun category_falls_back_to_its_id_when_names_have_not_been_fetched() =
        runTest {
            // The filter must still work offline before videoCategories.list has run.
            val store = VideoIndexStore(inMemoryDatabase(), Dispatchers.Unconfined)
            store.upsertAll(
                listOf(
                    com.ytindexer.shared.youtube.YouTubeVideo(
                        "v1",
                        "t",
                        "d",
                        "2026-01-01T00:00:00Z",
                        null,
                        emptyList(),
                        "28",
                        null,
                    ),
                ),
                NOW,
            )

            assertEquals("28", store.populatedCategories().single().title)
        }

    @Test
    fun clearing_removes_videos_categories_and_sync_state() =
        runTest {
            val db = inMemoryDatabase()
            val fake = FakeYouTube().apply { pagesByToken[null] = listOf("v1") to null }
            val (indexer, store) = indexer(fake, db)
            indexer.indexChannel()

            store.clear()

            assertEquals(0L, store.videoCount())
            assertTrue(db.syncStateQueries.selectAny().executeAsOneOrNull() == null)
        }
}
