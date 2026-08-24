package com.ytindexer.shared.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.index.VideoIndexStore
import com.ytindexer.shared.youtube.YouTubeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW = 1_000_000L

private fun video(
    id: String,
    title: String = "t",
    description: String = "d",
    tags: List<String> = emptyList(),
    categoryId: String? = "28",
    publishedAt: String = "2026-01-01T00:00:00Z",
) = YouTubeVideo(id, title, description, publishedAt, null, tags, categoryId, 100)

private class Fixture {
    val database: YtIndexerDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { driver ->
            YtIndexerDatabase.Schema.create(driver)
            YtIndexerDatabase(driver)
        }
    val store = VideoIndexStore(database, Dispatchers.Unconfined)
    val engine = SearchEngine(database, store, Dispatchers.Unconfined)

    suspend fun index(vararg videos: YouTubeVideo) = store.upsertAll(videos.toList(), NOW)

    fun addTranscript(
        videoId: String,
        text: String,
    ) {
        val row = database.videoQueries.selectById(videoId).executeAsOne()
        database.videoSearchQueries.deleteRow(videoId)
        database.videoSearchQueries.insertRow(videoId, row.title, row.description, row.tags, text)
        database.videoQueries.storeTranscript(text, NOW, NOW, row.contentHash, videoId)
    }
}

class SearchEngineTest {
    @Test
    fun finds_a_video_by_a_word_in_its_title() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Making sourdough at home"), video("v2", title = "Car review"))

            assertEquals(listOf("v1"), f.engine.search("sourdough").map { it.video.id })
        }

    @Test
    fun finds_a_video_by_a_word_only_in_its_description() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Baking", description = "we discuss sourdough starters"))

            assertEquals(listOf("v1"), f.engine.search("sourdough").map { it.video.id })
        }

    @Test
    fun finds_a_video_by_a_word_only_in_its_transcript() =
        runTest {
            // The whole point of paying 250 quota units per transcript: the word appears
            // nowhere in the title, description or tags.
            val f = Fixture()
            f.index(video("v1", title = "Vlog 42", description = "another day"))
            f.addTranscript("v1", "today I finally got my sourdough starter going")

            val results = f.engine.search("sourdough")

            assertEquals(listOf("v1"), results.map { it.video.id })
            assertTrue(results.single().matched.transcript, "the hit came from the transcript")
        }

    @Test
    fun prefix_matching_finds_partial_words_while_typing() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Making sourdough at home"))

            assertEquals(listOf("v1"), f.engine.search("sourd").map { it.video.id })
        }

    @Test
    fun title_matches_rank_above_description_matches() =
        runTest {
            val f = Fixture()
            f.index(
                video("v1", title = "Unrelated", description = "a passing mention of sourdough"),
                video("v2", title = "Sourdough masterclass", description = "unrelated"),
            )

            assertEquals(
                listOf("v2", "v1"),
                f.engine.search("sourdough").map { it.video.id },
                "a title match is a far stronger signal of intent",
            )
        }

    @Test
    fun a_title_match_outranks_a_mere_transcript_mention() =
        runTest {
            // Without weighting, any video that says the word once in an hour of speech
            // could outrank the video actually about it.
            val f = Fixture()
            f.index(
                video("v1", title = "Vlog", description = "day out"),
                video("v2", title = "Sourdough from scratch", description = "recipe"),
            )
            f.addTranscript("v1", "and then we grabbed some sourdough on the way home")

            assertEquals(listOf("v2", "v1"), f.engine.search("sourdough").map { it.video.id })
        }

    @Test
    fun all_terms_in_the_title_beats_scattered_partial_matches() =
        runTest {
            val f = Fixture()
            f.index(
                video("v1", title = "Sourdough bread guide"),
                video("v2", title = "Sourdough", description = "bread"),
            )

            assertEquals(listOf("v1", "v2"), f.engine.search("sourdough bread").map { it.video.id })
        }

    @Test
    fun multiple_terms_require_all_of_them() =
        runTest {
            // Implicit AND: an OR across common words would return most of the channel.
            val f = Fixture()
            f.index(
                video("v1", title = "Sourdough bread"),
                video("v2", title = "Sourdough pizza"),
            )

            assertEquals(listOf("v1"), f.engine.search("sourdough bread").map { it.video.id })
        }

    @Test
    fun search_is_case_insensitive() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Making SOURDOUGH"))

            assertEquals(1, f.engine.search("sourdough").size)
            assertEquals(1, f.engine.search("SoUrDoUgH").size)
        }

    @Test
    fun tags_are_searchable() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Vlog", tags = listOf("sourdough", "baking")))

            assertEquals(listOf("v1"), f.engine.search("sourdough").map { it.video.id })
        }

    // --- filters ------------------------------------------------------------------

    @Test
    fun category_narrows_a_prompt_search() =
        runTest {
            val f = Fixture()
            f.index(
                video("v1", title = "Sourdough", categoryId = "26"),
                video("v2", title = "Sourdough", categoryId = "28"),
            )

            assertEquals(listOf("v2"), f.engine.search("sourdough", categoryId = "28").map { it.video.id })
        }

    @Test
    fun empty_prompt_with_a_category_browses_that_category() =
        runTest {
            val f = Fixture()
            f.index(video("v1", categoryId = "26"), video("v2", categoryId = "28"))

            assertEquals(listOf("v2"), f.engine.search("", categoryId = "28").map { it.video.id })
        }

    @Test
    fun empty_prompt_and_no_category_returns_recent_videos() =
        runTest {
            // The screen should never be blank before the user types.
            val f = Fixture()
            f.index(
                video("v1", publishedAt = "2026-01-01T00:00:00Z"),
                video("v2", publishedAt = "2026-06-01T00:00:00Z"),
            )

            assertEquals(listOf("v2", "v1"), f.engine.search("").map { it.video.id })
        }

    @Test
    fun no_matches_returns_empty_rather_than_everything() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Sourdough"))

            assertEquals(emptyList(), f.engine.search("helicopter").map { it.video.id })
        }

    // --- hostile input --------------------------------------------------------------

    @Test
    fun punctuation_that_is_fts_syntax_does_not_crash_the_search() =
        runTest {
            // These are FTS operators or syntax errors if passed through raw.
            val f = Fixture()
            f.index(video("v1", title = "Sourdough"))

            for (prompt in listOf("\"", "sourdough\"", "-sourdough", "sourdough*", "*", "^", "(", "NEAR")) {
                f.engine.search(prompt) // must not throw
            }
        }

    @Test
    fun an_apostrophe_is_handled() =
        runTest {
            // "don't" is ordinary user text and must not break the query.
            val f = Fixture()
            f.index(video("v1", title = "Why I don't knead"))

            assertTrue(f.engine.search("don't").isNotEmpty())
        }

    @Test
    fun whitespace_only_prompt_is_treated_as_no_filter() =
        runTest {
            val f = Fixture()
            f.index(video("v1"), video("v2"))

            assertEquals(2, f.engine.search("   ").size)
        }

    @Test
    fun deleted_videos_disappear_from_search() =
        runTest {
            // A pruned video must leave the FTS index too, or search returns dead links.
            val f = Fixture()
            f.index(video("v1", title = "Sourdough"))
            assertEquals(1, f.engine.search("sourdough").size)

            f.store.pruneNotSeenSince(NOW + 1)

            assertEquals(emptyList(), f.engine.search("sourdough").map { it.video.id })
        }

    @Test
    fun re_indexing_does_not_duplicate_search_results() =
        runTest {
            val f = Fixture()
            f.index(video("v1", title = "Sourdough"))
            f.index(video("v1", title = "Sourdough"))

            assertEquals(1, f.engine.search("sourdough").size)
        }

    @Test
    fun re_indexing_preserves_a_transcript_in_the_search_index() =
        runTest {
            // Re-indexing metadata must not silently make a 250-unit transcript
            // unsearchable.
            val f = Fixture()
            f.index(video("v1", title = "Vlog"))
            f.addTranscript("v1", "we talk about sourdough here")

            f.index(video("v1", title = "Vlog"))

            assertEquals(listOf("v1"), f.engine.search("sourdough").map { it.video.id })
        }
}
