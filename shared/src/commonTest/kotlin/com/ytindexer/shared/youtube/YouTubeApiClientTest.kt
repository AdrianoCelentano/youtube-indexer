package com.ytindexer.shared.youtube

import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.auth.InMemoryTokenStore
import com.ytindexer.shared.auth.OAuthTokens
import com.ytindexer.shared.auth.TokenRefresher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

/** Records every request so tests can assert on quota-relevant call shapes. */
private class RecordingEngine(
    private val handler: (HttpRequestData, Int) -> Pair<HttpStatusCode, String>,
) {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient =
        HttpClient(
            MockEngine { request ->
                requests += request
                val (status, body) = handler(request, requests.size)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}

private fun authManager(
    accessToken: String = "access-1",
    expiresAt: Long = NOW + 3600,
    refresher: TokenRefresher = TokenRefresher { OAuthTokens("refreshed-token", "refresh-1", NOW + 3600) },
) = AuthManager(
    tokenStore = InMemoryTokenStore(OAuthTokens(accessToken, "refresh-1", expiresAt)),
    refresher = refresher,
    clock = Clock { NOW },
)

private fun ok(body: String) = HttpStatusCode.OK to body

class YouTubeApiClientTest {
    @Test
    fun channels_resolve_to_titles_and_uploads_playlists() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    ok(
                        """
                        {"items":[{"id":"UC123","snippet":{"title":"Some Channel"},
                        "contentDetails":{"relatedPlaylists":{"uploads":"UU123"}}}]}
                        """.trimIndent(),
                    )
                }
            val channel = YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC123")).single()

            assertEquals("UC123", channel.channelId)
            assertEquals("UU123", channel.uploadsPlaylistId)
            assertEquals("Some Channel", channel.title)
        }

    @Test
    fun a_channel_without_an_uploads_playlist_is_skipped_not_fatal() =
        runTest {
            // One dead subscription must not fail the whole sync.
            val engine = RecordingEngine { _, _ -> ok("""{"items":[{"id":"UC1"}]}""") }

            assertEquals(
                emptyList(),
                YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1")),
            )
        }

    @Test
    fun subscriptions_are_paged() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    ok("""{"items":[{"snippet":{"resourceId":{"channelId":"UC1"}}}],"nextPageToken":"P2"}""")
                }
            val page = YouTubeApiClient(engine.client(), authManager()).subscriptionPage()

            assertEquals(listOf("UC1"), page.items)
            assertEquals("P2", page.nextPageToken)
        }

    @Test
    fun requests_carry_the_bearer_token() =
        runTest {
            val engine = RecordingEngine { _, _ -> ok("""{"items":[]}""") }
            runCatching { YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1")) }

            assertEquals("Bearer access-1", engine.requests.single().headers["Authorization"])
        }

    @Test
    fun playlist_page_returns_ids_and_the_next_cursor() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    ok(
                        """
                        {"items":[{"contentDetails":{"videoId":"v1"}},{"contentDetails":{"videoId":"v2"}}],
                         "nextPageToken":"TOKEN2"}
                        """.trimIndent(),
                    )
                }
            val page = YouTubeApiClient(engine.client(), authManager()).playlistVideoIds("UU123")

            assertEquals(listOf("v1", "v2"), page.items)
            assertEquals("TOKEN2", page.nextPageToken)
            assertTrue(page.hasMore)
        }

    @Test
    fun last_playlist_page_has_no_cursor() =
        runTest {
            val engine = RecordingEngine { _, _ -> ok("""{"items":[{"contentDetails":{"videoId":"v1"}}]}""") }
            val page = YouTubeApiClient(engine.client(), authManager()).playlistVideoIds("UU123")

            assertNull(page.nextPageToken)
            assertEquals(false, page.hasMore)
        }

    @Test
    fun videos_are_fetched_in_one_batched_call() =
        runTest {
            // Quota: batching 50 ids into one call is the difference between ~40 units
            // and several thousand for a large channel.
            val engine = RecordingEngine { _, _ -> ok("""{"items":[]}""") }
            val ids = (1..50).map { "v$it" }

            YouTubeApiClient(engine.client(), authManager()).videosByIds(ids)

            assertEquals(1, engine.requests.size)
            assertEquals(
                ids.joinToString(","),
                engine.requests
                    .single()
                    .url.parameters["id"],
            )
        }

    @Test
    fun rejects_more_ids_than_the_api_accepts() =
        runTest {
            // Silently truncating would drop videos from the index with no signal.
            val engine = RecordingEngine { _, _ -> ok("""{"items":[]}""") }
            val tooMany = (1..51).map { "v$it" }

            assertFailsWith<IllegalArgumentException> {
                YouTubeApiClient(engine.client(), authManager()).videosByIds(tooMany)
            }
        }

    @Test
    fun empty_id_list_makes_no_request() =
        runTest {
            val engine = RecordingEngine { _, _ -> ok("""{"items":[]}""") }

            assertEquals(emptyList(), YouTubeApiClient(engine.client(), authManager()).videosByIds(emptyList()))
            assertEquals(0, engine.requests.size)
        }

    @Test
    fun never_calls_the_expensive_search_endpoint() =
        runTest {
            val engine = RecordingEngine { _, _ -> ok("""{"items":[]}""") }
            val client = YouTubeApiClient(engine.client(), authManager())

            runCatching { client.channels(listOf("UC1")) }
            client.playlistVideoIds("UU123")
            client.videosByIds(listOf("v1"))
            client.videoCategories()

            // search.list costs 100 quota units per call versus 1 for these.
            assertTrue(engine.requests.none { "search" in it.url.encodedPath })
        }

    @Test
    fun maps_video_fields_including_duration_and_best_thumbnail() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    ok(
                        """
                        {"items":[{"id":"v1","snippet":{"title":"T","description":"D",
                        "publishedAt":"2026-01-01T00:00:00Z","tags":["a","b"],"categoryId":"28",
                        "thumbnails":{"default":{"url":"low"},"maxres":{"url":"best"}}},
                        "contentDetails":{"duration":"PT1H2M3S"}}]}
                        """.trimIndent(),
                    )
                }
            val video = YouTubeApiClient(engine.client(), authManager()).videosByIds(listOf("v1")).single()

            assertEquals("T", video.title)
            assertEquals("D", video.description)
            assertEquals(listOf("a", "b"), video.tags)
            assertEquals("28", video.categoryId)
            assertEquals("best", video.thumbnailUrl)
            assertEquals(3723L, video.durationSeconds)
        }

    @Test
    fun tolerates_videos_with_missing_parts() =
        runTest {
            // Deleted or restricted videos come back sparse; they must not break indexing.
            val engine = RecordingEngine { _, _ -> ok("""{"items":[{"id":"v1"}]}""") }
            val video = YouTubeApiClient(engine.client(), authManager()).videosByIds(listOf("v1")).single()

            assertEquals("v1", video.id)
            assertEquals("", video.title)
            assertNull(video.durationSeconds)
            assertEquals(emptyList(), video.tags)
        }

    @Test
    fun categories_map_id_to_title() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    ok("""{"items":[{"id":"28","snippet":{"title":"Science & Technology"}}]}""")
                }
            val categories = YouTubeApiClient(engine.client(), authManager()).videoCategories()

            assertEquals(VideoCategory("28", "Science & Technology"), categories.single())
        }

    // --- 401 handling -----------------------------------------------------------

    @Test
    fun retries_once_with_a_refreshed_token_after_401() =
        runTest {
            val engine =
                RecordingEngine { _, attempt ->
                    if (attempt == 1) {
                        HttpStatusCode.Unauthorized to """{"error":{"code":401,"message":"Invalid Credentials"}}"""
                    } else {
                        ok("""{"items":[{"id":"UC1","contentDetails":{"relatedPlaylists":{"uploads":"UU1"}}}]}""")
                    }
                }

            val channel = YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1")).single()

            assertEquals("UU1", channel.uploadsPlaylistId)
            assertEquals(2, engine.requests.size)
            assertEquals("Bearer access-1", engine.requests[0].headers["Authorization"])
            assertEquals(
                "Bearer refreshed-token",
                engine.requests[1].headers["Authorization"],
                "the retry must use a freshly refreshed token, not the rejected one",
            )
        }

    @Test
    fun does_not_retry_forever_when_401_persists() =
        runTest {
            // A revoked grant would otherwise spin.
            val engine =
                RecordingEngine { _, _ ->
                    HttpStatusCode.Unauthorized to """{"error":{"code":401}}"""
                }

            assertFailsWith<YouTubeApiError.Unauthorized> {
                YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1"))
            }
            assertEquals(2, engine.requests.size, "exactly one retry, then give up")
        }

    // --- error mapping ----------------------------------------------------------

    @Test
    fun maps_quota_exceeded_distinctly_from_other_403s() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    HttpStatusCode.Forbidden to
                        """{"error":{"code":403,"message":"quota","errors":[{"reason":"quotaExceeded"}]}}"""
                }

            val error =
                assertFailsWith<YouTubeApiError.QuotaExceeded> {
                    YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1"))
                }
            assertEquals("quotaExceeded", error.reason)
        }

    @Test
    fun other_403s_are_not_reported_as_quota() =
        runTest {
            val engine =
                RecordingEngine { _, _ ->
                    HttpStatusCode.Forbidden to
                        """{"error":{"code":403,"errors":[{"reason":"forbidden"}]}}"""
                }

            assertFailsWith<YouTubeApiError.Forbidden> {
                YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1"))
            }
        }

    @Test
    fun server_errors_are_transient_so_callers_can_back_off() =
        runTest {
            val engine = RecordingEngine { _, _ -> HttpStatusCode.InternalServerError to """{}""" }

            assertFailsWith<YouTubeApiError.Transient> {
                YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1"))
            }
        }

    @Test
    fun rate_limiting_is_transient() =
        runTest {
            val engine = RecordingEngine { _, _ -> HttpStatusCode.TooManyRequests to """{}""" }

            assertFailsWith<YouTubeApiError.Transient> {
                YouTubeApiClient(engine.client(), authManager()).channels(listOf("UC1"))
            }
        }
}
