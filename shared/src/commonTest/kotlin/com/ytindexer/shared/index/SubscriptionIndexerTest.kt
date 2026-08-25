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
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

private fun database(): YtIndexerDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    YtIndexerDatabase.Schema.create(driver)
    return YtIndexerDatabase(driver)
}

/**
 * Serves subscriptions, channels, playlist pages and video details.
 *
 * @param videosPerChannel how many uploads each fake channel claims to have.
 */
private class FakeYouTube(
    private val channelIds: List<String>,
    private val videosPerChannel: Int,
) {
    var subscriptionCalls = 0
        private set
    var channelsCalls = 0
        private set
    val videoIdsRequested = mutableListOf<String>()
    var quotaExceeded = false

    fun client(): HttpClient =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                if (quotaExceeded) {
                    return@MockEngine respond(
                        """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

                val body =
                    when {
                        "subscriptions" in path -> {
                            subscriptionsJson()
                        }

                        "channels" in path -> {
                            channelsJson(request.url.parameters["id"].orEmpty())
                        }

                        "playlistItems" in path -> {
                            playlistJson(
                                request.url.parameters["playlistId"].orEmpty(),
                                request.url.parameters["pageToken"],
                            )
                        }

                        "videos" in path -> {
                            videosJson(request.url.parameters["id"].orEmpty())
                        }

                        else -> {
                            """{"items":[]}"""
                        }
                    }

                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun subscriptionsJson(): String {
        subscriptionCalls++
        val items = channelIds.joinToString(",") { """{"snippet":{"resourceId":{"channelId":"$it"}}}""" }
        return """{"items":[$items]}"""
    }

    private fun channelsJson(idParam: String): String {
        channelsCalls++
        val ids = idParam.split(",").filter { it.isNotBlank() }
        val items =
            ids.joinToString(",") {
                """{"id":"$it","snippet":{"title":"Channel $it"},
                   "contentDetails":{"relatedPlaylists":{"uploads":"UU$it"}}}"""
            }
        return """{"items":[$items]}"""
    }

    private fun playlistJson(
        playlistId: String,
        pageToken: String?,
    ): String {
        val channel = playlistId.removePrefix("UU")
        val page = pageToken?.removePrefix("p")?.toInt() ?: 0
        val start = page * PAGE_SIZE
        val ids = (start until minOf(start + PAGE_SIZE, videosPerChannel)).map { "$channel-v$it" }
        val items = ids.joinToString(",") { """{"contentDetails":{"videoId":"$it"}}""" }
        val hasMore = start + PAGE_SIZE < videosPerChannel
        val next = if (hasMore) ""","nextPageToken":"p${page + 1}"""" else ""
        return """{"items":[$items]$next}"""
    }

    private fun videosJson(idParam: String): String {
        val ids = idParam.split(",").filter { it.isNotBlank() }
        videoIdsRequested += ids
        val items =
            ids.joinToString(",") {
                val channel = it.substringBefore("-")
                """{"id":"$it","snippet":{"title":"T-$it","description":"d",
                   "publishedAt":"2026-01-01T00:00:00Z","categoryId":"28",
                   "channelId":"$channel","channelTitle":"Channel $channel"}}"""
            }
        return """{"items":[$items]}"""
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

private fun authManager() =
    AuthManager(
        tokenStore = InMemoryTokenStore(OAuthTokens("access", "refresh", NOW + 3600)),
        refresher = TokenRefresher { error("unused") },
        clock = Clock { NOW },
    )

class SubscriptionIndexerTest {
    private fun indexer(
        fake: FakeYouTube,
        db: YtIndexerDatabase,
        perChannel: Int = 200,
        now: Long = NOW,
    ): Pair<SubscriptionIndexer, VideoIndexStore> {
        val store = VideoIndexStore(db, Dispatchers.Unconfined)
        val api = YouTubeApiClient(fake.client(), authManager())
        return SubscriptionIndexer(api, store, db, Dispatchers.Unconfined, Clock { now }, perChannel) to store
    }

    @Test
    fun indexes_videos_from_every_subscribed_channel() =
        runTest {
            val fake = FakeYouTube(listOf("A", "B", "C"), videosPerChannel = 3)
            val db = database()
            val (indexer, store) = indexer(fake, db)

            val outcome = indexer.indexSubscriptions()

            assertIs<SubscriptionIndexOutcome.Completed>(outcome)
            assertEquals(3, outcome.channels)
            assertEquals(9L, store.videoCount())
        }

    @Test
    fun records_which_channel_each_video_came_from() =
        runTest {
            // Without this the channel filter and result rows have nothing to show.
            val fake = FakeYouTube(listOf("A"), videosPerChannel = 1)
            val db = database()
            val (indexer, store) = indexer(fake, db)

            indexer.indexSubscriptions()
            val video = store.recentVideos(limit = 10).single()

            assertEquals("A", video.channelId)
            assertEquals("Channel A", video.channelTitle)
        }

    @Test
    fun stops_at_the_per_channel_cap() =
        runTest {
            // A prolific channel must not swamp the index or the results.
            val fake = FakeYouTube(listOf("A"), videosPerChannel = 500)
            val db = database()
            val (indexer, store) = indexer(fake, db, perChannel = 120)

            indexer.indexSubscriptions()

            assertEquals(120L, store.videoCount())
        }

    @Test
    fun the_cap_keeps_the_newest_videos() =
        runTest {
            // The uploads playlist is newest-first, so an early stop must keep the front.
            val fake = FakeYouTube(listOf("A"), videosPerChannel = 200)
            val db = database()
            val (indexer, _) = indexer(fake, db, perChannel = 10)

            indexer.indexSubscriptions()

            assertTrue(fake.videoIdsRequested.all { it.substringAfter("-v").toInt() < 10 })
        }

    @Test
    fun channel_lookups_are_batched_rather_than_one_call_each() =
        runTest {
            // 60 channels is 2 batched calls, not 60.
            val fake = FakeYouTube((1..60).map { "C$it" }, videosPerChannel = 1)
            val db = database()
            val (indexer, _) = indexer(fake, db)

            indexer.indexSubscriptions()

            assertEquals(2, fake.channelsCalls)
        }

    @Test
    fun never_calls_the_expensive_search_endpoint() =
        runTest {
            val fake = FakeYouTube(listOf("A"), videosPerChannel = 1)
            val (indexer, _) = indexer(fake, database())

            indexer.indexSubscriptions()

            // search.list costs 100 units per call versus 1 for everything used here.
            assertEquals(0, fake.videoIdsRequested.count { it.contains("search") })
        }

    @Test
    fun quota_exhaustion_reports_progress_rather_than_failing_outright() =
        runTest {
            val fake = FakeYouTube(listOf("A"), videosPerChannel = 1).apply { quotaExceeded = true }
            val (indexer, _) = indexer(fake, database())

            assertIs<SubscriptionIndexOutcome.QuotaExhausted>(indexer.indexSubscriptions())
        }

    @Test
    fun unsubscribed_channels_videos_are_pruned_on_the_next_run() =
        runTest {
            val db = database()

            indexer(FakeYouTube(listOf("A", "B"), videosPerChannel = 2), db).first.indexSubscriptions()
            assertEquals(4L, VideoIndexStore(db, Dispatchers.Unconfined).videoCount())

            // B unsubscribed: its videos must not linger in search results.
            val (indexer2, store2) = indexer(FakeYouTube(listOf("A"), videosPerChannel = 2), db, now = NOW + 60)
            val outcome = indexer2.indexSubscriptions()

            assertIs<SubscriptionIndexOutcome.Completed>(outcome)
            assertEquals(2L, outcome.videosPruned)
            assertEquals(2L, store2.videoCount())
        }

    @Test
    fun re_running_updates_rather_than_duplicating() =
        runTest {
            val db = database()
            indexer(FakeYouTube(listOf("A"), videosPerChannel = 3), db).first.indexSubscriptions()
            indexer(FakeYouTube(listOf("A"), videosPerChannel = 3), db, now = NOW + 60).first.indexSubscriptions()

            assertEquals(3L, VideoIndexStore(db, Dispatchers.Unconfined).videoCount())
        }

    @Test
    fun reports_progress_per_channel() =
        runTest {
            val fake = FakeYouTube(listOf("A", "B"), videosPerChannel = 1)
            val (indexer, _) = indexer(fake, database())

            val seen = mutableListOf<SubscriptionProgress>()
            indexer.indexSubscriptions { seen += it }

            assertEquals(2, seen.size)
            assertEquals(listOf(1, 2), seen.map { it.channelsDone })
            assertEquals(listOf("Channel A", "Channel B"), seen.map { it.currentChannel })
        }

    @Test
    fun channels_with_indexed_videos_are_listed_for_filtering() =
        runTest {
            val db = database()
            val (indexer, store) = indexer(FakeYouTube(listOf("A", "B"), videosPerChannel = 2), db)
            indexer.indexSubscriptions()

            val channels = store.populatedChannels()

            assertEquals(2, channels.size)
            assertTrue(channels.all { it.videoCount == 2L })
        }
}
