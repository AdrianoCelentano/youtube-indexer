package com.ytindexer.shared.index

import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.youtube.VideoCategory
import com.ytindexer.shared.youtube.YouTubeVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Local store for indexed videos.
 *
 * Wraps the generated SQLDelight queries so callers work in domain types and never see
 * the storage encoding (for example tags being newline-joined).
 */
class VideoIndexStore(
    private val database: YtIndexerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Writes a batch in a single transaction.
     *
     * One transaction per batch rather than per row: a 50-video page is one fsync
     * instead of fifty, and a crash mid-batch leaves the page either fully applied or
     * not at all, which is what makes resuming from the stored cursor correct.
     */
    suspend fun upsertAll(
        videos: List<YouTubeVideo>,
        indexedAtEpochSeconds: Long,
    ) = withContext(ioDispatcher) {
        database.transaction {
            videos.forEach { video ->
                database.videoQueries.upsert(
                    videoId = video.id,
                    title = video.title,
                    description = video.description,
                    publishedAt = video.publishedAt,
                    thumbnailUrl = video.thumbnailUrl,
                    tags = video.tags.joinToString(TAG_SEPARATOR),
                    categoryId = video.categoryId,
                    durationSeconds = video.durationSeconds,
                    indexedAt = indexedAtEpochSeconds,
                    contentHash = contentHashOf(video),
                    videoId_ = video.id,
                )
            }
        }
    }

    suspend fun upsertCategories(categories: List<VideoCategory>) =
        withContext(ioDispatcher) {
            database.transaction {
                categories.forEach { database.categoryQueries.upsert(it.id, it.title) }
            }
        }

    /** How many videos have a transcript stored. */
    suspend fun transcribedCount(): Long =
        withContext(ioDispatcher) {
            database.videoQueries.countWithTranscript().executeAsOne()
        }

    suspend fun videoCount(): Long =
        withContext(ioDispatcher) {
            database.videoQueries.countAll().executeAsOne()
        }

    suspend fun recentVideos(
        limit: Long,
        offset: Long = 0,
    ): List<YouTubeVideo> =
        withContext(ioDispatcher) {
            database.videoQueries
                .selectAll(limit, offset)
                .executeAsList()
                .map { it.toDomain() }
        }

    suspend fun videosInCategory(
        categoryId: String,
        limit: Long,
        offset: Long = 0,
    ): List<YouTubeVideo> =
        withContext(ioDispatcher) {
            database.videoQueries
                .selectByCategory(categoryId, limit, offset)
                .executeAsList()
                .map { it.toDomain() }
        }

    /** Only categories that actually contain indexed videos, so the filter shows no dead options. */
    suspend fun populatedCategories(): List<CategoryWithCount> =
        withContext(ioDispatcher) {
            val titles =
                database.categoryQueries
                    .selectAll()
                    .executeAsList()
                    .associate { it.categoryId to it.title }

            database.videoQueries
                .selectPopulatedCategoryIds()
                .executeAsList()
                .mapNotNull { row ->
                    val id = row.categoryId ?: return@mapNotNull null
                    CategoryWithCount(
                        categoryId = id,
                        // Fall back to the raw id if videoCategories.list hasn't been
                        // fetched yet, so the filter still works offline.
                        title = titles[id] ?: id,
                        videoCount = row.videoCount,
                    )
                }
        }

    suspend fun newestIndexedPublishedAt(): String? =
        withContext(ioDispatcher) {
            database.videoQueries
                .maxPublishedAt()
                .executeAsOne()
                .MAX
        }

    /**
     * Removes videos not seen by the full index that started at [runStartedAtEpochSeconds].
     *
     * A completed run stamps every surviving video with the run's start time, so anything
     * older was not returned by YouTube and has been deleted or made private upstream.
     * Without this, deleted videos stay in the index forever and surface in search results
     * pointing at dead links.
     *
     * @return how many rows were removed.
     */
    suspend fun pruneNotSeenSince(runStartedAtEpochSeconds: Long): Long =
        withContext(ioDispatcher) {
            database.transactionWithResult {
                val before = database.videoQueries.countAll().executeAsOne()
                database.videoQueries.deleteIndexedBefore(runStartedAtEpochSeconds)
                before - database.videoQueries.countAll().executeAsOne()
            }
        }

    /** Videos whose vector is missing or was built by a different model. */
    suspend fun videosNeedingEmbedding(
        model: String,
        limit: Long,
    ): List<YouTubeVideo> =
        withContext(ioDispatcher) {
            database.videoQueries
                .selectNeedingEmbedding(model, limit)
                .executeAsList()
                .map { it.toDomain() }
        }

    /** Wipes the index. Used on sign-out and when switching accounts. */
    suspend fun clear() =
        withContext(ioDispatcher) {
            database.transaction {
                database.videoQueries.deleteAll()
                database.categoryQueries.deleteAll()
                database.syncStateQueries.deleteAll()
            }
        }

    internal companion object {
        /**
         * Newline, because YouTube tags may contain commas but never newlines.
         * A comma separator would corrupt any tag containing one.
         */
        const val TAG_SEPARATOR = "\n"
    }
}

data class CategoryWithCount(
    val categoryId: String,
    val title: String,
    val videoCount: Long,
)

internal fun com.ytindexer.shared.db.Video.toDomainVideo(): YouTubeVideo = toDomain()

private fun com.ytindexer.shared.db.Video.toDomain(): YouTubeVideo =
    YouTubeVideo(
        id = videoId,
        title = title,
        description = description,
        publishedAt = publishedAt,
        thumbnailUrl = thumbnailUrl,
        tags = if (tags.isEmpty()) emptyList() else tags.split(VideoIndexStore.TAG_SEPARATOR),
        categoryId = categoryId,
        durationSeconds = durationSeconds,
    )
