package com.ytindexer.android.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytindexer.shared.index.SubscriptionIndexOutcome
import com.ytindexer.shared.index.SubscriptionIndexer
import com.ytindexer.shared.index.VideoIndexStore
import com.ytindexer.shared.youtube.YouTubeApiError
import com.ytindexer.shared.youtube.YouTubeVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sync panel should be showing. */
sealed interface SyncUiState {
    /** @param indexedTotal videos already in the local index from previous runs. */
    data class Idle(
        val indexedTotal: Long,
    ) : SyncUiState

    data class Running(
        val videosIndexed: Int,
        val channelsDone: Int,
        val channelsTotal: Int,
        val currentChannel: String,
    ) : SyncUiState

    /**
     * @param sample a few real videos, shown so the response mapping can be eyeballed
     *   against actual YouTube data rather than assumed correct.
     */
    data class Done(
        val videosIndexed: Int,
        val channels: Int,
        val indexedTotal: Long,
        val sample: List<YouTubeVideo>,
    ) : SyncUiState

    /** Daily quota spent. Does not reset until midnight Pacific, so retrying now is pointless. */
    data class QuotaExhausted(
        val videosIndexed: Int,
        val indexedTotal: Long,
    ) : SyncUiState

    data class Failed(
        val message: String,
        val videosIndexed: Int,
        val indexedTotal: Long,
    ) : SyncUiState
}

class SyncViewModel(
    private val indexer: SubscriptionIndexer,
    private val store: VideoIndexStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle(indexedTotal = 0))
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = SyncUiState.Idle(store.videoCount())
        }
    }

    fun sync() {
        viewModelScope.launch {
            _uiState.value =
                SyncUiState.Running(
                    videosIndexed = 0,
                    channelsDone = 0,
                    channelsTotal = 0,
                    currentChannel = "",
                )

            _uiState.value =
                try {
                    val outcome =
                        indexer.indexSubscriptions { progress ->
                            _uiState.value =
                                SyncUiState.Running(
                                    videosIndexed = progress.videosIndexed,
                                    channelsDone = progress.channelsDone,
                                    channelsTotal = progress.channelsTotal,
                                    currentChannel = progress.currentChannel,
                                )
                        }

                    when (outcome) {
                        is SubscriptionIndexOutcome.Completed -> {
                            SyncUiState.Done(
                                videosIndexed = outcome.videosIndexed,
                                channels = outcome.channels,
                                indexedTotal = store.videoCount(),
                                sample = store.recentVideos(limit = SAMPLE_SIZE),
                            )
                        }

                        is SubscriptionIndexOutcome.QuotaExhausted -> {
                            SyncUiState.QuotaExhausted(outcome.videosIndexed, store.videoCount())
                        }

                        is SubscriptionIndexOutcome.Interrupted -> {
                            SyncUiState.Failed(
                                outcome.cause.message ?: "Sync interrupted",
                                outcome.videosIndexed,
                                store.videoCount(),
                            )
                        }
                    }
                } catch (e: YouTubeApiError) {
                    // myChannel() and refreshCategories() can still throw: an account
                    // with no channel, or a dead grant.
                    SyncUiState.Failed(e.message ?: "Sync failed", 0, store.videoCount())
                }
        }
    }

    /** Drops the local index, e.g. before re-indexing from scratch. */
    fun clearIndex() {
        viewModelScope.launch {
            store.clear()
            _uiState.value = SyncUiState.Idle(store.videoCount())
        }
    }

    private companion object {
        const val SAMPLE_SIZE = 3L
    }
}
