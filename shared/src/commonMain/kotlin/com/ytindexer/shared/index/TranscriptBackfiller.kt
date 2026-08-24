package com.ytindexer.shared.index

import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.db.YtIndexerDatabase
import com.ytindexer.shared.quota.QuotaCost
import com.ytindexer.shared.quota.QuotaLedger
import com.ytindexer.shared.youtube.CaptionTrack
import com.ytindexer.shared.youtube.YouTubeApiClient
import com.ytindexer.shared.youtube.YouTubeApiError
import com.ytindexer.shared.youtube.captionsToPlainText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class BackfillResult(
    val transcriptsFetched: Int,
    val videosWithoutCaptions: Int,
    val quotaUnitsSpent: Int,
    val stoppedForBudget: Boolean,
    /** Set when Google, rather than our own accounting, ended the run. */
    val quotaError: YouTubeApiError.QuotaExceeded? = null,
)

/**
 * Fetches transcripts a few at a time, within a daily quota budget.
 *
 * Transcripts cost **250 units each** ([QuotaCost.TRANSCRIPT_PER_VIDEO]) against a
 * 10,000/day allowance, so roughly 40 per day are affordable and a large channel
 * backfills over weeks. That is why this is deliberately *not* part of indexing: folding
 * it in would exhaust the day's quota on the first sync and leave ordinary metadata
 * indexing failing with `quotaExceeded`.
 *
 * Newest videos are done first, on the assumption that recent uploads are searched most.
 */
class TranscriptBackfiller(
    private val api: YouTubeApiClient,
    private val database: YtIndexerDatabase,
    private val ledger: QuotaLedger,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) {
    /**
     * @param maxVideos upper bound regardless of budget, so a single run cannot block for
     *   minutes on a large channel.
     */
    suspend fun backfill(maxVideos: Int = DEFAULT_BATCH): BackfillResult {
        val affordable = ledger.transcriptsAffordableToday()
        val candidates =
            if (affordable == 0) {
                emptyList()
            } else {
                loadCandidates(minOf(affordable, maxVideos).toLong())
            }

        var fetched = 0
        var missing = 0
        var spent = 0
        var stopped = affordable == 0
        var quotaError: YouTubeApiError.QuotaExceeded? = null

        val remaining = candidates.iterator()
        while (remaining.hasNext() && !stopped) {
            when (val step = attempt(remaining.next().videoId)) {
                is Step.OutOfBudget -> {
                    stopped = true
                    quotaError = step.reportedByGoogle
                }

                // Transient failure: left unattempted so a flaky network cannot
                // permanently mark a video as having no captions.
                Step.SkipForNow -> {
                    Unit
                }

                is Step.Fetched -> {
                    spent += step.unitsSpent
                    if (step.hasTranscript) fetched++ else missing++
                }
            }
        }

        return BackfillResult(fetched, missing, spent, stopped, quotaError)
    }

    private sealed interface Step {
        data class OutOfBudget(
            val reportedByGoogle: YouTubeApiError.QuotaExceeded?,
        ) : Step

        data object SkipForNow : Step

        data class Fetched(
            val hasTranscript: Boolean,
            val unitsSpent: Int,
        ) : Step
    }

    private suspend fun attempt(videoId: String): Step {
        if (!ledger.canSpend(QuotaCost.TRANSCRIPT_PER_VIDEO, QuotaCost.RESERVED_FOR_INDEXING)) {
            return Step.OutOfBudget(reportedByGoogle = null)
        }

        return try {
            val outcome = fetchOne(videoId)
            Step.Fetched(outcome.text != null, outcome.unitsSpent)
        } catch (e: YouTubeApiError.QuotaExceeded) {
            // Google disagrees with our accounting; believe Google.
            Step.OutOfBudget(reportedByGoogle = e)
        } catch (
            @Suppress("SwallowedException") e: YouTubeApiError.Transient,
        ) {
            // Deliberately not surfaced: this video is simply retried on a later pass.
            Step.SkipForNow
        }
    }

    private class FetchOutcome(
        val text: String?,
        val unitsSpent: Int,
    )

    private suspend fun fetchOne(videoId: String): FetchOutcome {
        val tracks = api.captionTracks(videoId)
        ledger.record(QuotaCost.CAPTIONS_LIST)

        val track = pickTrack(tracks)
        if (track == null) {
            // Record the attempt so a video with no captions is not retried forever at
            // 50 units a time.
            markUnavailable(videoId)
            return FetchOutcome(null, QuotaCost.CAPTIONS_LIST)
        }

        val raw = api.downloadCaption(track.id)
        ledger.record(QuotaCost.CAPTIONS_DOWNLOAD)

        val text = captionsToPlainText(raw)
        if (text.isBlank()) {
            markUnavailable(videoId)
            return FetchOutcome(null, QuotaCost.TRANSCRIPT_PER_VIDEO)
        }

        storeTranscript(videoId, text)
        return FetchOutcome(text, QuotaCost.TRANSCRIPT_PER_VIDEO)
    }

    /**
     * Prefers a human-authored track over an auto-generated one: ASR output is noticeably
     * less accurate, and accuracy matters more here than coverage since this text is what
     * search matches against. Drafts are skipped -- they are unpublished and may be
     * incomplete.
     */
    private fun pickTrack(tracks: List<CaptionTrack>): CaptionTrack? {
        val usable = tracks.filterNot { it.isDraft }
        return usable.firstOrNull { !it.isAutoGenerated } ?: usable.firstOrNull()
    }

    private suspend fun loadCandidates(limit: Long) =
        withContext(ioDispatcher) {
            database.videoQueries.selectNeedingTranscript(limit).executeAsList()
        }

    private suspend fun storeTranscript(
        videoId: String,
        text: String,
    ) = withContext(ioDispatcher) {
        val now = clock.nowEpochSeconds()
        val video = database.videoQueries.selectById(videoId).executeAsOneOrNull()

        // Refresh the search row so a transcript that cost 250 quota units is actually
        // searchable; without this it would sit in the table but never match.
        video?.let {
            database.videoSearchQueries.deleteRow(videoId)
            database.videoSearchQueries.insertRow(
                videoId = videoId,
                title = it.title,
                description = it.description,
                tags = it.tags.replace('\n', ' '),
                transcript = text,
            )
        }

        database.videoQueries.storeTranscript(
            transcript = text,
            transcriptFetchedAt = now,
            transcriptAttemptedAt = now,
            // The transcript is part of the text a search embeds, so folding it into the
            // hash marks the video for re-embedding. Without this a video would keep the
            // vector built from its description alone.
            contentHash = video?.let { contentHashOf(it.toDomainVideo(), transcript = text) },
            videoId = videoId,
        )
    }

    private suspend fun markUnavailable(videoId: String) =
        withContext(ioDispatcher) {
            database.videoQueries.markTranscriptUnavailable(clock.nowEpochSeconds(), videoId)
        }

    private companion object {
        /** Keeps a single run short; the scheduler decides how often to call it. */
        const val DEFAULT_BATCH = 10
    }
}
