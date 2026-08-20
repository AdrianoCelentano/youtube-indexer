package com.ytindexer.shared.youtube

/**
 * A video from the signed-in user's own channel, flattened from the YouTube API's
 * nested `snippet`/`contentDetails` shape into what the indexer and search actually need.
 */
data class YouTubeVideo(
    val id: String,
    val title: String,
    val description: String,
    val publishedAt: String,
    val thumbnailUrl: String?,
    val tags: List<String>,
    val categoryId: String?,
    val durationSeconds: Long?,
)

/** One page of results plus the cursor needed to fetch the next. */
data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
) {
    val hasMore: Boolean get() = nextPageToken != null
}

/** A YouTube video category, e.g. "Science & Technology". */
data class VideoCategory(
    val id: String,
    val title: String,
)

/** The signed-in user's channel, including the playlist that holds all their uploads. */
data class Channel(
    val id: String,
    val title: String,
    /**
     * Playlist containing every public upload on the channel. This is the cheap way to
     * enumerate a channel: `search.list` costs 100 quota units per call, whereas paging
     * this playlist costs 1.
     */
    val uploadsPlaylistId: String,
)
