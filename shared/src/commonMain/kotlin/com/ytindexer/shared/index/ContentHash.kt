package com.ytindexer.shared.index

import com.ytindexer.shared.youtube.YouTubeVideo

/**
 * Stable fingerprint of the text a semantic search would embed.
 *
 * Purpose: tell whether a video's *content* changed, as opposed to when it was last
 * fetched. `indexedAt` moves on every sync, so it cannot answer "does this vector still
 * represent this video?" -- editing a description would otherwise leave a stale
 * embedding with nothing to flag it.
 *
 * FNV-1a rather than [String.hashCode]: hashCode's contract permits it to differ between
 * platforms and JDK versions, and a hash that changes underneath stored data would
 * silently invalidate every embedding on an unrelated toolchain upgrade. This is
 * deterministic everywhere and needs no crypto dependency -- collision resistance against
 * an attacker is not required here, only change detection.
 */
fun contentHashOf(video: YouTubeVideo): String =
    fnv1a64(
        buildString {
            append(video.title)
            append(FIELD_SEPARATOR)
            append(video.description)
            append(FIELD_SEPARATOR)
            // Order matters: reordering tags is a real change to the embedded text.
            video.tags.joinTo(this, TAG_JOIN)
        },
    )

private fun fnv1a64(value: String): String {
    var hash = FNV_OFFSET_BASIS
    for (char in value) {
        hash = hash xor char.code.toLong()
        hash *= FNV_PRIME
    }
    return hash.toULong().toString(HEX_RADIX)
}

/**
 * Unit separator: cannot appear in YouTube text, so "ab" + "" cannot collide with
 * "a" + "b".
 */
private const val FIELD_SEPARATOR = ''
private const val TAG_JOIN = ""

private const val FNV_OFFSET_BASIS = -3_750_763_034_362_895_579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1_099_511_628_211L
private const val HEX_RADIX = 16
