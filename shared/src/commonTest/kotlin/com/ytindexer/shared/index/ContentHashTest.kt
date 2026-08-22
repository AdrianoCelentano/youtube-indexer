package com.ytindexer.shared.index

import com.ytindexer.shared.youtube.YouTubeVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private fun video(
    title: String = "t",
    description: String = "d",
    tags: List<String> = listOf("a", "b"),
) = YouTubeVideo("v1", title, description, "2026-01-01T00:00:00Z", null, tags, "28", 100)

class ContentHashTest {
    @Test
    fun identical_content_hashes_identically() {
        assertEquals(contentHashOf(video()), contentHashOf(video()))
    }

    @Test
    fun hash_is_pinned_to_a_known_value() {
        // Pinned deliberately. Changing the algorithm changes every hash, which would
        // mark every stored embedding stale and trigger a full, billable re-embed. If
        // this test fails, that is a decision to make consciously, not a refactor to
        // wave through.
        assertEquals("3a223d32184ca4b4", contentHashOf(video()))
    }

    @Test
    fun changing_the_title_changes_the_hash() {
        assertNotEquals(contentHashOf(video(title = "a")), contentHashOf(video(title = "b")))
    }

    @Test
    fun changing_the_description_changes_the_hash() {
        // The case that matters: an edited description must invalidate its vector.
        assertNotEquals(
            contentHashOf(video(description = "old")),
            contentHashOf(video(description = "new")),
        )
    }

    @Test
    fun changing_tags_changes_the_hash() {
        assertNotEquals(
            contentHashOf(video(tags = listOf("a"))),
            contentHashOf(video(tags = listOf("a", "b"))),
        )
    }

    @Test
    fun reordering_tags_changes_the_hash() {
        // Order is part of the embedded text, so it is a real content change.
        assertNotEquals(
            contentHashOf(video(tags = listOf("a", "b"))),
            contentHashOf(video(tags = listOf("b", "a"))),
        )
    }

    @Test
    fun field_boundaries_cannot_be_forged() {
        // Without a separator, title "ab" + description "" would collide with
        // title "a" + description "b".
        assertNotEquals(
            contentHashOf(video(title = "ab", description = "", tags = emptyList())),
            contentHashOf(video(title = "a", description = "b", tags = emptyList())),
        )
    }

    @Test
    fun unrelated_metadata_does_not_affect_the_hash() {
        // Only the embedded text matters; a new thumbnail must not force a re-embed.
        val a = video().copy(thumbnailUrl = "one", durationSeconds = 1)
        val b = video().copy(thumbnailUrl = "two", durationSeconds = 999)

        assertEquals(contentHashOf(a), contentHashOf(b))
    }
}
