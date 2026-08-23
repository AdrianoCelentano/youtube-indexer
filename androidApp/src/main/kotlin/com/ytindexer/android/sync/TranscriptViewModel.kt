package com.ytindexer.android.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytindexer.android.auth.GoogleAuthConfig
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.index.TranscriptBackfiller
import com.ytindexer.shared.index.VideoIndexStore
import com.ytindexer.shared.quota.QuotaCost
import com.ytindexer.shared.quota.QuotaLedger
import com.ytindexer.shared.youtube.YouTubeApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TranscriptUiState {
    data object Loading : TranscriptUiState

    /**
     * The stored grant predates the `force-ssl` scope, so transcripts cannot be
     * downloaded until the user re-consents.
     *
     * Requesting a scope does not widen an existing grant, so without this the API would
     * simply return 403 and look like a bug.
     */
    data object NeedsReauthorisation : TranscriptUiState

    data class Idle(
        val transcribed: Long,
        val totalVideos: Long,
        val affordableToday: Int,
        val quotaUsedToday: Long,
    ) : TranscriptUiState

    data object Running : TranscriptUiState

    data class Done(
        val fetched: Int,
        val withoutCaptions: Int,
        val unitsSpent: Int,
        val stoppedForBudget: Boolean,
        val transcribed: Long,
        val totalVideos: Long,
    ) : TranscriptUiState

    data class Failed(
        val message: String,
    ) : TranscriptUiState
}

class TranscriptViewModel(
    private val backfiller: TranscriptBackfiller,
    private val store: VideoIndexStore,
    private val ledger: QuotaLedger,
    private val authManager: AuthManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TranscriptUiState>(TranscriptUiState.Loading)
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.value =
                if (!authManager.hasGrantedScope(GoogleAuthConfig.YOUTUBE_FORCE_SSL_SCOPE)) {
                    TranscriptUiState.NeedsReauthorisation
                } else {
                    idleState()
                }
        }
    }

    private suspend fun idleState() =
        TranscriptUiState.Idle(
            transcribed = store.transcribedCount(),
            totalVideos = store.videoCount(),
            affordableToday = ledger.transcriptsAffordableToday(),
            quotaUsedToday = ledger.usedToday(),
        )

    fun fetchTranscripts() {
        viewModelScope.launch {
            _uiState.value = TranscriptUiState.Running
            _uiState.value =
                try {
                    val result = backfiller.backfill()
                    TranscriptUiState.Done(
                        fetched = result.transcriptsFetched,
                        withoutCaptions = result.videosWithoutCaptions,
                        unitsSpent = result.quotaUnitsSpent,
                        stoppedForBudget = result.stoppedForBudget,
                        transcribed = store.transcribedCount(),
                        totalVideos = store.videoCount(),
                    )
                } catch (
                    @Suppress("SwallowedException") e: YouTubeApiError.Unauthorized,
                ) {
                    // Deliberately not shown verbatim: a 403/401 here almost always means
                    // the grant lacks force-ssl, and "sign in again" is more actionable
                    // than Google's message.
                    TranscriptUiState.NeedsReauthorisation
                } catch (e: YouTubeApiError) {
                    TranscriptUiState.Failed(e.message ?: "Transcript fetch failed")
                }
        }
    }

    /** Units one batch would cost at most, for the UI to show before the user commits. */
    fun costPerTranscript(): Int = QuotaCost.TRANSCRIPT_PER_VIDEO
}
