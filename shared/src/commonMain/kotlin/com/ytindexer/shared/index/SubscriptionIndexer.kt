package com.ytindexer.shared.index

import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.youtube.SubscribedChannel
import com.ytindexer.shared.youtube.YouTubeApiClient
import com.ytindexer.shared.youtube.YouTubeApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class SubscriptionProgress(
    val channelsDone: Int,
    val channelsTotal: Int,
    val videosIndexed: Int,
    val currentChannel: String,
)

sealed class SubscriptionIndexOutcome {
    data class Completed(
        val channels: Int,
        val videosIndexed: Int,
        val videosPruned: Long,
    ) : SubscriptionIndexOutcome()

    data class QuotaExhausted(
        val channelsDone: Int,
        val videosIndexed: Int,
        val cause: YouTubeApiError.QuotaExceeded,
    ) : SubscriptionIndexOutcome()

    data class Interrupted(
        val channelsDone: Int,
        val videosIndexed: Int,
        val cause: YouTubeApiError,
    ) : SubscriptionIndexOutcome()
}

/**
 * Indexes the most recent videos from every channel the user subscribes to.
 *
 * **Capped per channel** rather than exhaustive. A single channel with thousands of
 * uploads would otherwise dominate both the index and the search results, while the
 * videos anyone actually looks for are overwhelmingly recent. The cap also keeps a full
 * refresh to minutes rather than tens of minutes.
 *
 * Quota is not the constraint here, unlike transcripts: subscriptions, channels,
 * playlists and video details all cost 1 unit per call of up to 50 items, so tens of
 * thousands of videos cost a few thousand units. Time and storage are what the cap
 * protects.
 */
class SubscriptionIndexer(
    private val api: YouTubeApiClient,
    private val store: VideoIndexStore,
    private val database: YtIndexerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val videosPerChannel: Int = DEFAULT_VIDEOS_PER_CHANNEL,
) {
    suspend fun indexSubscriptions(onProgress: (SubscriptionProgress) -> Unit = {}): SubscriptionIndexOutcome {
        val runStartedAt = clock.nowEpochSeconds()
        var indexed = 0
        var done = 0

        return try {
            val channels = loadSubscribedChannels()

            for (channel in channels) {
                indexed += indexChannel(channel, runStartedAt)
                done++
                onProgress(SubscriptionProgress(done, channels.size, indexed, channel.title))
            }

            // Only safe after every channel has been walked: pruning earlier would
            // delete videos from channels this run had not reached yet.
            SubscriptionIndexOutcome.Completed(
                channels = channels.size,
                videosIndexed = indexed,
                videosPruned = store.pruneNotSeenSince(runStartedAt),
            )
        } catch (e: YouTubeApiError.QuotaExceeded) {
            // Channels already walked keep their videos; the next run redoes the rest.
            SubscriptionIndexOutcome.QuotaExhausted(done, indexed, e)
        } catch (e: YouTubeApiError.Transient) {
            // A persistent network fault should stop the run rather than hammer every
            // remaining channel in turn.
            SubscriptionIndexOutcome.Interrupted(done, indexed, e)
        }
    }

    /** All subscriptions, resolved to their uploads playlists. */
    private suspend fun loadSubscribedChannels(): List<SubscribedChannel> {
        val channelIds = mutableListOf<String>()
        var pageToken: String? = null

        do {
            val page = api.subscriptionPage(pageToken)
            channelIds += page.items
            pageToken = page.nextPageToken
        } while (pageToken != null)

        // Batched 50 at a time: one call per fifty channels rather than one per channel.
        return channelIds
            .chunked(YouTubeApiClient.MAX_IDS_PER_REQUEST)
            .flatMap { api.channels(it) }
    }

    /** @return how many videos were stored for this channel. */
    private suspend fun indexChannel(
        channel: SubscribedChannel,
        runStartedAt: Long,
    ): Int {
        var pageToken: String? = null
        var indexed = 0

        while (indexed < videosPerChannel) {
            val page = api.playlistVideoIds(channel.uploadsPlaylistId, pageToken)
            // The uploads playlist is newest-first, so stopping early keeps the most
            // recent videos rather than an arbitrary slice.
            val wanted = page.items.take(videosPerChannel - indexed)

            if (wanted.isNotEmpty()) {
                val videos =
                    api.videosByIds(wanted).map { video ->
                        // playlistItems does not carry the channel reliably, and the
                        // uploads playlist is the channel's by definition.
                        video.copy(
                            channelId = video.channelId ?: channel.channelId,
                            channelTitle = video.channelTitle ?: channel.title,
                        )
                    }
                store.upsertAll(videos, runStartedAt)
                indexed += videos.size
            }

            if (!page.hasMore) break
            pageToken = page.nextPageToken
        }

        saveChannelState(channel, indexed, runStartedAt)
        return indexed
    }

    private suspend fun saveChannelState(
        channel: SubscribedChannel,
        indexed: Int,
        runStartedAt: Long,
    ) = withContext(ioDispatcher) {
        database.syncStateQueries.upsert(
            channelId = channel.channelId,
            uploadsPlaylistId = channel.uploadsPlaylistId,
            lastCompletedAt = clock.nowEpochSeconds(),
            pendingPageToken = null,
            fullIndexCompleted = 1L,
            videosIndexed = indexed.toLong(),
            fullIndexStartedAt = runStartedAt,
        )
    }

    companion object {
        /**
         * Recent videos kept per subscribed channel.
         *
         * Chosen so a few hundred subscriptions land in the low tens of thousands of
         * videos: fast to search, quick to refresh, and no single prolific channel
         * swamping results.
         */
        const val DEFAULT_VIDEOS_PER_CHANNEL: Int = 200
    }
}
