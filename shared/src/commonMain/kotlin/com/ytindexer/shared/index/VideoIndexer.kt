package com.ytindexer.shared.index

import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.youtube.YouTubeApiClient
import com.ytindexer.shared.youtube.YouTubeApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Progress of an indexing run, for the UI to show. */
data class IndexProgress(
    val videosIndexed: Int,
    val pagesFetched: Int,
    val complete: Boolean,
)

/** Why a run stopped early, when it did. */
sealed class IndexOutcome {
    data class Completed(
        val videosIndexed: Int,
    ) : IndexOutcome()

    /**
     * Ran out of daily quota. The cursor is saved, so the next run resumes rather than
     * restarting the channel. Quota resets at midnight Pacific.
     */
    data class QuotaExhausted(
        val videosIndexed: Int,
        val cause: YouTubeApiError.QuotaExceeded,
    ) : IndexOutcome()

    /** Network or server failure. Cursor saved; retrying later is safe. */
    data class Interrupted(
        val videosIndexed: Int,
        val cause: YouTubeApiError,
    ) : IndexOutcome()
}

/**
 * Walks the signed-in user's uploads playlist and stores every video locally.
 *
 * **Resumable by design.** A large channel cannot be indexed in one request, and a run
 * can die from quota exhaustion, a dropped network, or the OS killing the process. After
 * each page is written the playlist cursor is persisted in the same transaction boundary,
 * so the next run continues from there instead of re-fetching (and re-spending quota on)
 * everything already done.
 */
class VideoIndexer(
    private val api: YouTubeApiClient,
    private val store: VideoIndexStore,
    private val database: YtIndexerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) {
    /**
     * Indexes the channel, resuming from the stored cursor if a previous run was cut short.
     *
     * @param onProgress called after each page so the UI can show movement on what may
     *   be a multi-minute operation.
     */
    suspend fun indexChannel(onProgress: (IndexProgress) -> Unit = {}): IndexOutcome {
        val channel = api.myChannel()
        val resumeToken = loadResumeToken(channel.id)

        var pageToken = resumeToken
        var indexed = 0
        var pages = 0

        return try {
            while (true) {
                val page = api.playlistVideoIds(channel.uploadsPlaylistId, pageToken)

                if (page.items.isNotEmpty()) {
                    val videos = api.videosByIds(page.items)
                    store.upsertAll(videos, clock.nowEpochSeconds())
                    indexed += videos.size
                }

                pages++

                // Persist the cursor only after the page's videos are stored. Saving it
                // first would skip that page forever if the process died in between.
                saveProgress(
                    channelId = channel.id,
                    uploadsPlaylistId = channel.uploadsPlaylistId,
                    nextToken = page.nextPageToken,
                    completed = !page.hasMore,
                    videosIndexed = indexed,
                )

                onProgress(IndexProgress(indexed, pages, complete = !page.hasMore))

                if (!page.hasMore) break
                pageToken = page.nextPageToken
            }
            IndexOutcome.Completed(indexed)
        } catch (e: YouTubeApiError.QuotaExceeded) {
            // Cursor is already saved, so the next run resumes rather than restarting.
            IndexOutcome.QuotaExhausted(indexed, e)
        } catch (e: YouTubeApiError.Transient) {
            IndexOutcome.Interrupted(indexed, e)
        }
    }

    /**
     * Refreshes the cached category names.
     *
     * Failures are swallowed: categories are cosmetic labels, and losing them should not
     * fail an otherwise successful index. The filter falls back to raw category ids.
     */
    suspend fun refreshCategories(regionCode: String = "US") {
        val categories =
            try {
                api.videoCategories(regionCode)
            } catch (
                @Suppress("SwallowedException") e: YouTubeApiError,
            ) {
                // Intentional: category names are cosmetic labels. Losing them must not
                // fail an otherwise successful index -- the filter falls back to raw ids.
                return
            }
        store.upsertCategories(categories)
    }

    private suspend fun loadResumeToken(channelId: String): String? =
        withContext(ioDispatcher) {
            database.syncStateQueries
                .selectByChannel(channelId)
                .executeAsOneOrNull()
                // A finished index has no cursor; a fresh run starts from the top.
                ?.takeIf { it.fullIndexCompleted == 0L }
                ?.pendingPageToken
        }

    private suspend fun saveProgress(
        channelId: String,
        uploadsPlaylistId: String,
        nextToken: String?,
        completed: Boolean,
        videosIndexed: Int,
    ) = withContext(ioDispatcher) {
        database.syncStateQueries.upsert(
            channelId = channelId,
            uploadsPlaylistId = uploadsPlaylistId,
            lastCompletedAt = if (completed) clock.nowEpochSeconds() else null,
            pendingPageToken = if (completed) null else nextToken,
            fullIndexCompleted = if (completed) 1L else 0L,
            videosIndexed = videosIndexed.toLong(),
        )
    }
}
