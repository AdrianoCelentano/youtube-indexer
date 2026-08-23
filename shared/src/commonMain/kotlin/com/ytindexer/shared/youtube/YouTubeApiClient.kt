package com.ytindexer.shared.youtube

import com.ytindexer.shared.auth.AuthError
import com.ytindexer.shared.auth.AuthManager
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.SerializationException

/**
 * Read-only client for the YouTube Data API v3, scoped to the signed-in user's own
 * channel.
 *
 * **Quota shape matters here.** The default allowance is 10,000 units/day and
 * `search.list` costs 100 units per call, while `playlistItems.list` and `videos.list`
 * cost 1 each. This client therefore never calls `search.list`: it enumerates the channel
 * by paging the uploads playlist and batches video lookups [MAX_IDS_PER_REQUEST] at a
 * time. Indexing a 1,000-video channel costs roughly 40 units this way versus several
 * thousand via search.
 */
class YouTubeApiClient(
    private val httpClient: HttpClient,
    private val authManager: AuthManager,
    private val baseUrl: String = YOUTUBE_API_BASE,
) {
    /** The signed-in user's channel, including the uploads playlist used for indexing. */
    suspend fun myChannel(): Channel {
        val response =
            request<ListResponse<ChannelDto>>("channels") {
                parameter("part", "snippet,contentDetails")
                parameter("mine", "true")
            }

        val channel =
            response.items.firstOrNull()
                ?: throw YouTubeApiError.NotFound("Signed-in account has no YouTube channel")

        val uploads =
            channel.contentDetails?.relatedPlaylists?.uploads
                ?: throw YouTubeApiError.Unexpected("Channel ${channel.id} has no uploads playlist")

        return Channel(
            id = channel.id,
            title = channel.snippet?.title.orEmpty(),
            uploadsPlaylistId = uploads,
        )
    }

    /**
     * One page of video IDs from a playlist.
     *
     * Returns IDs rather than full videos because `playlistItems` snippets are less
     * complete than `videos` ones; callers pair this with [videosByIds].
     */
    suspend fun playlistVideoIds(
        playlistId: String,
        pageToken: String? = null,
        pageSize: Int = MAX_IDS_PER_REQUEST,
    ): Page<String> {
        val response =
            request<ListResponse<PlaylistItemDto>>("playlistItems") {
                parameter("part", "contentDetails")
                parameter("playlistId", playlistId)
                parameter("maxResults", pageSize.coerceIn(1, MAX_IDS_PER_REQUEST))
                pageToken?.let { parameter("pageToken", it) }
            }

        return Page(
            items = response.items.mapNotNull { it.contentDetails?.videoId },
            nextPageToken = response.nextPageToken,
        )
    }

    /**
     * Full details for up to [MAX_IDS_PER_REQUEST] videos in a single call.
     *
     * @throws IllegalArgumentException if more IDs are passed than the API accepts --
     *   silently truncating would drop videos from the index without any signal.
     */
    suspend fun videosByIds(ids: List<String>): List<YouTubeVideo> {
        require(ids.size <= MAX_IDS_PER_REQUEST) {
            "videos.list accepts at most $MAX_IDS_PER_REQUEST ids, got ${ids.size}"
        }
        if (ids.isEmpty()) return emptyList()

        val response =
            request<ListResponse<VideoDto>>("videos") {
                parameter("part", "snippet,contentDetails")
                parameter("id", ids.joinToString(","))
                parameter("maxResults", ids.size)
            }

        return response.items.map { it.toDomain() }
    }

    /**
     * Category id -> human-readable name, for the category filter.
     *
     * @param regionCode categories are region-scoped; titles are localised per region.
     */
    suspend fun videoCategories(regionCode: String = "US"): List<VideoCategory> {
        val response =
            request<ListResponse<VideoCategoryDto>>("videoCategories") {
                parameter("part", "snippet")
                parameter("regionCode", regionCode)
            }

        return response.items.mapNotNull { dto ->
            dto.snippet?.title?.let { VideoCategory(dto.id, it) }
        }
    }

    /**
     * Caption tracks available for a video. **Costs 50 quota units** -- see [QuotaCost].
     */
    suspend fun captionTracks(videoId: String): List<CaptionTrack> {
        val response =
            request<ListResponse<CaptionDto>>("captions") {
                parameter("part", "snippet")
                parameter("videoId", videoId)
            }

        return response.items.map { dto ->
            CaptionTrack(
                id = dto.id,
                language = dto.snippet?.language,
                isAutoGenerated = dto.snippet?.trackKind.equals("ASR", ignoreCase = true),
                isDraft = dto.snippet?.isDraft ?: false,
            )
        }
    }

    /**
     * Downloads a caption track as text. **Costs 200 quota units** -- the single most
     * expensive call this app makes, roughly 200x a page of video metadata.
     *
     * Requires the `youtube.force-ssl` scope and permission to edit the video, which the
     * signed-in user has for their own uploads.
     */
    suspend fun downloadCaption(
        captionId: String,
        format: String = SRT_FORMAT,
    ): String {
        val token = obtainToken(staleToken = null)
        var response =
            send("captions/$captionId", token) {
                parameter("tfmt", format)
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            response =
                send("captions/$captionId", obtainToken(staleToken = token)) {
                    parameter("tfmt", format)
                }
        }

        if (!response.status.isSuccess()) throw response.toApiError()

        // Caption downloads return the track body, not JSON, so this bypasses the usual
        // typed parsing.
        return response.bodyAsText()
    }

    /**
     * Performs an authorised GET, retrying once on 401.
     *
     * A 401 means the access token was rejected mid-flight -- typically it expired
     * between the expiry check and the request landing, or was revoked server-side. The
     * retry forces a refresh and tries again exactly once; a second 401 is treated as
     * genuinely unauthorised rather than retried again, so a revoked grant cannot spin.
     */
    private suspend inline fun <reified T> request(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit,
    ): T {
        val token = obtainToken(staleToken = null)
        var response = send(path, token) { configure() }

        if (response.status == HttpStatusCode.Unauthorized) {
            // Pass the rejected token so a concurrent caller's refresh is reused rather
            // than triggering a second one.
            val refreshed = obtainToken(staleToken = token)
            response = send(path, refreshed) { configure() }
        }

        if (!response.status.isSuccess()) throw response.toApiError()

        return parseBody<T>(response, path)
    }

    private suspend inline fun <reified T> parseBody(
        response: HttpResponse,
        path: String,
    ): T =
        try {
            response.body<T>()
        } catch (e: SerializationException) {
            throw YouTubeApiError.Unexpected("Could not parse $path response", e)
        } catch (e: NoTransformationFoundException) {
            throw YouTubeApiError.Unexpected("Unexpected content type from $path", e)
        }

    /**
     * @param staleToken null for the first attempt; the rejected token when retrying
     *   after a 401, which forces a refresh.
     */
    private suspend fun obtainToken(staleToken: String?): String =
        try {
            if (staleToken == null) authManager.accessToken() else authManager.forceRefresh(staleToken)
        } catch (e: AuthError.RefreshRejected) {
            // The grant is dead; AuthManager has already cleared the session.
            throw YouTubeApiError.Unauthorized(e)
        } catch (e: AuthError.NotSignedIn) {
            throw YouTubeApiError.Unauthorized(e)
        } catch (e: AuthError.Network) {
            throw YouTubeApiError.Transient("Could not refresh access token", e)
        }

    private suspend fun send(
        path: String,
        token: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        try {
            httpClient.get("$baseUrl/$path") {
                header("Authorization", "Bearer $token")
                configure()
            }
        } catch (e: IOException) {
            throw YouTubeApiError.Transient("Network failure calling $path", e)
        }

    companion object {
        const val YOUTUBE_API_BASE: String = "https://www.googleapis.com/youtube/v3"

        /** `videos.list` accepts at most 50 ids per call. */
        const val MAX_IDS_PER_REQUEST: Int = 50

        private const val SRT_FORMAT = "srt"
    }
}

internal fun VideoDto.toDomain(): YouTubeVideo =
    YouTubeVideo(
        id = id,
        title = snippet?.title.orEmpty(),
        description = snippet?.description.orEmpty(),
        publishedAt = snippet?.publishedAt.orEmpty(),
        thumbnailUrl = snippet?.thumbnails?.bestUrl(),
        tags = snippet?.tags.orEmpty(),
        categoryId = snippet?.categoryId,
        durationSeconds = parseIsoDurationSeconds(contentDetails?.duration),
    )

internal suspend fun HttpResponse.toApiError(): YouTubeApiError {
    val envelope = runCatching { body<ApiErrorEnvelope>() }.getOrNull()
    val reason =
        envelope
            ?.error
            ?.errors
            ?.firstOrNull()
            ?.reason
    val message = envelope?.error?.message ?: "HTTP ${status.value}"

    return when {
        status == HttpStatusCode.Unauthorized -> {
            YouTubeApiError.Unauthorized()
        }

        status == HttpStatusCode.Forbidden && reason in QUOTA_REASONS -> {
            YouTubeApiError.QuotaExceeded(reason)
        }

        status == HttpStatusCode.Forbidden -> {
            YouTubeApiError.Forbidden(reason)
        }

        status == HttpStatusCode.NotFound -> {
            YouTubeApiError.NotFound(message)
        }

        // 5xx and 429 are worth retrying with backoff; 4xx generally is not.
        status.value >= HTTP_SERVER_ERROR_MIN || status == HttpStatusCode.TooManyRequests -> {
            YouTubeApiError.Transient(message)
        }

        else -> {
            YouTubeApiError.Unexpected("$message (reason=$reason)")
        }
    }
}

private val QUOTA_REASONS = setOf("quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded")

/** 5xx and above are server-side, so worth retrying with backoff. */
private const val HTTP_SERVER_ERROR_MIN = 500
