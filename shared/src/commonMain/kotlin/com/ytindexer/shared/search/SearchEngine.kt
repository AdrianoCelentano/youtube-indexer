package com.ytindexer.shared.search

import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.index.VideoIndexStore
import com.ytindexer.shared.youtube.YouTubeVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where a query matched, which is what justifies a result's position. */
data class MatchFields(
    val title: Boolean,
    val tags: Boolean,
    val description: Boolean,
    val transcript: Boolean,
)

data class SearchResult(
    val video: YouTubeVideo,
    val score: Int,
    val matched: MatchFields,
)

/**
 * Searches the local index by free-text prompt, category, or both.
 *
 * Two stages. SQLite's FTS4 index does candidate retrieval, which is what makes it fast
 * over thousands of videos. Ranking then happens in Kotlin, because FTS4 has no built-in
 * bm25 and because a hand-written scorer is easier to explain, test and tune per field
 * than a ranking function buried in SQL.
 */
class SearchEngine(
    private val database: YtIndexerDatabase,
    private val store: VideoIndexStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * @param prompt free text; blank means no text filter.
     * @param categoryId restricts to one category; null means all.
     *
     * Prompt and category combine as AND. An empty prompt with a category is a browse; an
     * empty prompt with no category returns the most recent videos, so the screen is
     * never blank before the user types.
     */
    suspend fun search(
        prompt: String = "",
        categoryId: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<SearchResult> {
        val ftsQuery = buildFtsQuery(prompt)

        if (ftsQuery == null) {
            return browse(categoryId, limit).map {
                SearchResult(it, score = 0, matched = NO_MATCH)
            }
        }

        val candidates = matchingVideos(ftsQuery, limit)
        val terms = searchTerms(prompt)

        return candidates
            .asSequence()
            .filter { categoryId == null || it.categoryId == categoryId }
            .map { video -> score(video, terms) }
            // Ties broken by recency: among equally relevant videos the newest is the
            // more likely target.
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenByDescending { it.video.publishedAt })
            .take(limit)
            .toList()
    }

    private suspend fun browse(
        categoryId: String?,
        limit: Int,
    ): List<YouTubeVideo> =
        if (categoryId == null) {
            store.recentVideos(limit.toLong())
        } else {
            store.videosInCategory(categoryId, limit.toLong())
        }

    private suspend fun matchingVideos(
        ftsQuery: String,
        limit: Int,
    ): List<YouTubeVideo> =
        withContext(ioDispatcher) {
            // Over-fetch: the category filter and ranking run after retrieval, so taking
            // exactly `limit` candidates could leave a full page short.
            val ids =
                database.videoSearchQueries
                    .matching(ftsQuery, (limit * CANDIDATE_MULTIPLIER).toLong())
                    .executeAsList()

            // FTS columns are untyped, so SQLDelight models videoId as nullable.
            ids.mapNotNull { row ->
                row.videoId?.let { id ->
                    database.videoQueries
                        .selectById(id)
                        .executeAsOneOrNull()
                        ?.let(::toDomain)
                }
            }
        }

    /**
     * Field weights, highest first: a title match is a much stronger signal of intent
     * than a word appearing somewhere in a long transcript.
     *
     * Transcripts score lowest precisely because they are long -- without that, any video
     * that merely mentions a word in passing would outrank one actually about it.
     */
    private fun score(
        video: YouTubeVideo,
        terms: List<String>,
    ): SearchResult {
        val title = video.title.lowercase()
        val tags = video.tags.joinToString(" ").lowercase()
        val description = video.description.lowercase()

        var total = 0
        var inTitle = false
        var inTags = false
        var inDescription = false

        for (term in terms) {
            if (title.contains(term)) {
                total += TITLE_WEIGHT
                inTitle = true
            }
            if (tags.contains(term)) {
                total += TAG_WEIGHT
                inTags = true
            }
            if (description.contains(term)) {
                total += DESCRIPTION_WEIGHT
                inDescription = true
            }
        }

        // Every term present in the title is a much better result than a scattering of
        // partial matches, so reward completeness rather than counting hits alone.
        if (terms.isNotEmpty() && terms.all { title.contains(it) }) total += ALL_TERMS_IN_TITLE_BONUS

        return SearchResult(
            video = video,
            score = total,
            matched =
                MatchFields(
                    title = inTitle,
                    tags = inTags,
                    description = inDescription,
                    // FTS already matched this row, so if none of the visible fields
                    // contain the terms the hit must have come from the transcript.
                    transcript = !inTitle && !inTags && !inDescription,
                ),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val CANDIDATE_MULTIPLIER = 4

        const val TITLE_WEIGHT = 10
        const val TAG_WEIGHT = 5
        const val DESCRIPTION_WEIGHT = 2
        const val ALL_TERMS_IN_TITLE_BONUS = 15

        val NO_MATCH = MatchFields(title = false, tags = false, description = false, transcript = false)
    }
}

/** The same terms [buildFtsQuery] searched for, for scoring against. */
internal fun searchTerms(prompt: String): List<String> =
    buildFtsQuery(prompt)
        ?.split(" ")
        ?.map { it.removeSuffix("*") }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

private fun toDomain(row: com.ytindexer.shared.db.Video): YouTubeVideo =
    YouTubeVideo(
        id = row.videoId,
        title = row.title,
        description = row.description,
        publishedAt = row.publishedAt,
        thumbnailUrl = row.thumbnailUrl,
        tags = if (row.tags.isEmpty()) emptyList() else row.tags.split("\n"),
        categoryId = row.categoryId,
        durationSeconds = row.durationSeconds,
    )
