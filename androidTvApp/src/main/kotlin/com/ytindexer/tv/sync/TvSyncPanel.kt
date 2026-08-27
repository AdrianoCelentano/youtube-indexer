package com.ytindexer.tv.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.ytindexer.ui.Dimens
import com.ytindexer.ui.sync.SyncUiState

/**
 * Sync controls for the TV screen.
 *
 * A single-line status strip rather than the phone app's full panel (which also lists a
 * few sample videos to eyeball the response mapping): that job is already done by the
 * phone app, and here it would just crowd out the result grid below it.
 */
@Composable
internal fun TvSyncPanel(
    state: SyncUiState,
    onSyncClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = statusText(state), style = MaterialTheme.typography.bodyMedium)

        when (state) {
            is SyncUiState.Idle -> {
                Button(onClick = onSyncClick) { Text("Sync subscriptions") }
            }

            is SyncUiState.Running -> {
                // No action while a sync is running -- just the status text above.
            }

            is SyncUiState.Done, is SyncUiState.QuotaExhausted, is SyncUiState.Failed -> {
                Button(onClick = onSyncClick) { Text("Sync again") }
                OutlinedButton(onClick = onClearClick) { Text("Clear index") }
            }
        }
    }
}

private fun statusText(state: SyncUiState): String =
    when (state) {
        is SyncUiState.Idle -> {
            indexedLabel(state.indexedTotal)
        }

        is SyncUiState.Running -> {
            if (state.channelsTotal == 0) {
                "Finding your subscriptions…"
            } else {
                "${state.channelsDone}/${state.channelsTotal} channels · ${state.videosIndexed} videos"
            }
        }

        is SyncUiState.Done -> {
            "Indexed ${state.videosIndexed} videos from ${state.channels} channels (${state.indexedTotal} stored)"
        }

        is SyncUiState.QuotaExhausted -> {
            "Daily quota used up after ${state.videosIndexed} videos. Resumes at midnight Pacific."
        }

        is SyncUiState.Failed -> {
            state.message
        }
    }

private fun indexedLabel(total: Long): String = if (total == 0L) "No videos indexed yet." else "$total videos indexed."
