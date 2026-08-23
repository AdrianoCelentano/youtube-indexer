package com.ytindexer.shared.youtube

/**
 * Strips SRT/VTT markup down to the spoken words.
 *
 * Timestamps, cue numbers and positioning tags are noise for both full-text search and
 * embeddings -- worse than noise for embeddings, since they would consume tokens and
 * dilute the meaning of the text being vectorised.
 *
 * Auto-generated captions also repeat lines as the rolling caption window advances, so
 * consecutive duplicates are collapsed. Without that, a transcript can contain each
 * phrase two or three times, which skews term frequency in FTS ranking.
 */
fun captionsToPlainText(raw: String): String {
    val words =
        raw
            .lineSequence()
            .map { it.trim() }
            .filterNot { it.isEmpty() }
            .filterNot { it == WEBVTT_HEADER }
            .filterNot { it.isCueNumber() }
            .filterNot { it.isTimestampLine() }
            .filterNot { it.startsWith(NOTE_PREFIX) }
            .map { it.stripInlineTags() }
            .filterNot { it.isEmpty() }

    return buildString {
        var previous: String? = null
        for (line in words) {
            // Rolling auto-caption windows repeat the previous line verbatim.
            if (line == previous) continue
            if (isNotEmpty()) append(' ')
            append(line)
            previous = line
        }
    }
}

private fun String.isCueNumber(): Boolean = all { it.isDigit() }

private fun String.isTimestampLine(): Boolean = contains(ARROW)

/** Removes karaoke timing tags such as `<00:00:01.234>` and `<c>`/`</c>` styling. */
private fun String.stripInlineTags(): String = replace(TAG_REGEX, "").trim()

private const val WEBVTT_HEADER = "WEBVTT"
private const val NOTE_PREFIX = "NOTE "
private const val ARROW = "-->"
private val TAG_REGEX = Regex("<[^>]*>")
