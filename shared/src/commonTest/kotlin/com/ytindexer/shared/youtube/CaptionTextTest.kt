package com.ytindexer.shared.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionTextTest {
    @Test
    fun strips_srt_cue_numbers_and_timestamps() {
        val srt =
            """
            1
            00:00:01,000 --> 00:00:04,000
            Today we are making sourdough.

            2
            00:00:04,500 --> 00:00:07,000
            Start with the starter.
            """.trimIndent()

        assertEquals(
            "Today we are making sourdough. Start with the starter.",
            captionsToPlainText(srt),
        )
    }

    @Test
    fun strips_the_webvtt_header_and_inline_tags() {
        val vtt =
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            <c.colorE5E5E5>Hello</c> <00:00:02.000>everyone
            """.trimIndent()

        assertEquals("Hello everyone", captionsToPlainText(vtt))
    }

    @Test
    fun collapses_the_repeated_lines_auto_captions_produce() {
        // Rolling ASR windows repeat each phrase as the window advances. Left in, they
        // inflate term frequency and skew FTS ranking.
        val vtt =
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            making sourdough

            00:00:03.000 --> 00:00:05.000
            making sourdough

            00:00:05.000 --> 00:00:07.000
            at home
            """.trimIndent()

        assertEquals("making sourdough at home", captionsToPlainText(vtt))
    }

    @Test
    fun keeps_a_repeated_phrase_that_is_genuinely_repeated_later() {
        // Only *consecutive* duplicates are window artefacts; a phrase said again later
        // is real content.
        val srt =
            """
            1
            00:00:01,000 --> 00:00:02,000
            knead it

            2
            00:00:02,000 --> 00:00:03,000
            then rest

            3
            00:00:03,000 --> 00:00:04,000
            knead it
            """.trimIndent()

        assertEquals("knead it then rest knead it", captionsToPlainText(srt))
    }

    @Test
    fun drops_vtt_note_blocks() {
        val vtt = "WEBVTT\n\nNOTE this is a comment\n\n00:00:01.000 --> 00:00:02.000\nreal text"

        assertEquals("real text", captionsToPlainText(vtt))
    }

    @Test
    fun empty_input_produces_empty_output() {
        assertEquals("", captionsToPlainText(""))
        assertEquals("", captionsToPlainText("WEBVTT\n\n"))
    }

    @Test
    fun output_contains_no_timestamps() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nspoken words here"
        val text = captionsToPlainText(srt)

        assertFalse(text.contains("-->"))
        assertFalse(text.contains("00:00"))
        assertTrue(text.contains("spoken words here"))
    }
}
