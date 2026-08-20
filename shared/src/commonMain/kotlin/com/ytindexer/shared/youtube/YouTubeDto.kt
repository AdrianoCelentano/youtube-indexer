package com.ytindexer.shared.youtube

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire types for the YouTube Data API v3.
 *
 * Kept internal and separate from the domain models in YouTubeModels.kt so the API's
 * deeply nested shape doesn't leak into the indexer and search layers, and so a response
 * change is a mapping fix rather than a refactor.
 *
 * Almost every field is nullable with a default: parts are only present if requested,
 * and Google adds fields over time.
 */

@Serializable
internal data class ListResponse<T>(
    @SerialName("items") val items: List<T> = emptyList(),
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    @SerialName("pageInfo") val pageInfo: PageInfo? = null,
)

@Serializable
internal data class PageInfo(
    @SerialName("totalResults") val totalResults: Int? = null,
    @SerialName("resultsPerPage") val resultsPerPage: Int? = null,
)

@Serializable
internal data class ChannelDto(
    @SerialName("id") val id: String,
    @SerialName("snippet") val snippet: SnippetDto? = null,
    @SerialName("contentDetails") val contentDetails: ChannelContentDetailsDto? = null,
)

@Serializable
internal data class ChannelContentDetailsDto(
    @SerialName("relatedPlaylists") val relatedPlaylists: RelatedPlaylistsDto? = null,
)

@Serializable
internal data class RelatedPlaylistsDto(
    @SerialName("uploads") val uploads: String? = null,
)

@Serializable
internal data class PlaylistItemDto(
    @SerialName("contentDetails") val contentDetails: PlaylistItemContentDetailsDto? = null,
)

@Serializable
internal data class PlaylistItemContentDetailsDto(
    @SerialName("videoId") val videoId: String? = null,
)

@Serializable
internal data class VideoDto(
    @SerialName("id") val id: String,
    @SerialName("snippet") val snippet: SnippetDto? = null,
    @SerialName("contentDetails") val contentDetails: VideoContentDetailsDto? = null,
)

@Serializable
internal data class VideoContentDetailsDto(
    /** ISO-8601 duration, e.g. `PT4M13S`. */
    @SerialName("duration") val duration: String? = null,
)

@Serializable
internal data class SnippetDto(
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("publishedAt") val publishedAt: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("categoryId") val categoryId: String? = null,
    @SerialName("thumbnails") val thumbnails: ThumbnailsDto? = null,
)

@Serializable
internal data class ThumbnailsDto(
    @SerialName("default") val default: ThumbnailDto? = null,
    @SerialName("medium") val medium: ThumbnailDto? = null,
    @SerialName("high") val high: ThumbnailDto? = null,
    @SerialName("standard") val standard: ThumbnailDto? = null,
    @SerialName("maxres") val maxres: ThumbnailDto? = null,
) {
    /** Best available thumbnail, largest first. */
    fun bestUrl(): String? = maxres?.url ?: standard?.url ?: high?.url ?: medium?.url ?: default?.url
}

@Serializable
internal data class ThumbnailDto(
    @SerialName("url") val url: String? = null,
)

@Serializable
internal data class VideoCategoryDto(
    @SerialName("id") val id: String,
    @SerialName("snippet") val snippet: VideoCategorySnippetDto? = null,
)

@Serializable
internal data class VideoCategorySnippetDto(
    @SerialName("title") val title: String? = null,
)

/** Error envelope returned by the API on 4xx/5xx. */
@Serializable
internal data class ApiErrorEnvelope(
    @SerialName("error") val error: ApiErrorDto? = null,
)

@Serializable
internal data class ApiErrorDto(
    @SerialName("code") val code: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("errors") val errors: List<ApiErrorItemDto> = emptyList(),
)

@Serializable
internal data class ApiErrorItemDto(
    @SerialName("reason") val reason: String? = null,
    @SerialName("domain") val domain: String? = null,
)
