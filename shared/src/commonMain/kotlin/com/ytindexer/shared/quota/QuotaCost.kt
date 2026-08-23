package com.ytindexer.shared.quota

/**
 * Quota cost of each YouTube Data API method this app calls, in units.
 *
 * Values are from Google's published cost table. The spread is enormous and drives most
 * of the indexing design:
 *
 * - Listing a playlist page or 50 video details costs **1** unit each.
 * - Fetching one video's transcript costs **250** (list 50 + download 200).
 *
 * Against a default allowance of 10,000 units/day that is roughly 40 transcripts per day
 * versus ~40 units to index a thousand videos' metadata. Captions therefore cannot be
 * part of a normal sync; they backfill separately under a budget.
 */
object QuotaCost {
    const val CHANNELS_LIST: Int = 1
    const val PLAYLIST_ITEMS_LIST: Int = 1
    const val VIDEOS_LIST: Int = 1
    const val VIDEO_CATEGORIES_LIST: Int = 1

    const val CAPTIONS_LIST: Int = 50
    const val CAPTIONS_DOWNLOAD: Int = 200

    /** Total cost of obtaining one transcript. */
    const val TRANSCRIPT_PER_VIDEO: Int = CAPTIONS_LIST + CAPTIONS_DOWNLOAD

    /** Google's default daily allowance for a new project. */
    const val DEFAULT_DAILY_LIMIT: Int = 10_000

    /**
     * Units held back from the caption backfill so ordinary indexing still works.
     *
     * Without this, a backfill would consume the entire allowance and the next
     * incremental sync would fail with quotaExceeded -- the expensive optional feature
     * would break the cheap essential one.
     */
    const val RESERVED_FOR_INDEXING: Int = 2_000
}
