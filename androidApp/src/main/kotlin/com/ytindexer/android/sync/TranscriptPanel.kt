package com.ytindexer.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ytindexer.ui.Dimens

/**
 * Transcript backfill controls.
 *
 * Deliberately shows the quota cost up front. At 250 units per transcript against a
 * 10,000/day allowance, a tap here spends a visible fraction of the day's budget -- very
 * unlike the rest of the app, where a full index costs about 40 units.
 */
@Composable
internal fun TranscriptPanel(
    state: TranscriptUiState,
    onFetchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider()
        Text(text = "Transcripts", style = MaterialTheme.typography.titleSmall)

        when (state) {
            TranscriptUiState.Loading -> {
                CircularProgressIndicator()
            }

            TranscriptUiState.NeedsReauthorisation -> {
                Text(
                    text =
                        "Transcripts need extra permission. Sign out and sign in again to " +
                            "grant it — your existing sign-in predates it.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is TranscriptUiState.Idle -> {
                IdleContent(state, onFetchClick)
            }

            TranscriptUiState.Running -> {
                CircularProgressIndicator()
                Text(
                    text = "Downloading captions…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is TranscriptUiState.Done -> {
                DoneContent(state, onFetchClick)
            }

            is TranscriptUiState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    state: TranscriptUiState.Idle,
    onFetchClick: () -> Unit,
) {
    Text(
        text = "${state.transcribed} of ${state.totalVideos} videos transcribed",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text =
            "${state.affordableToday} affordable today " +
                "(${state.quotaUsedToday} of 10,000 units used)",
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onFetchClick,
        enabled = state.affordableToday > 0 && state.transcribed < state.totalVideos,
    ) { Text("Fetch transcripts") }
}

@Composable
private fun DoneContent(
    state: TranscriptUiState.Done,
    onFetchClick: () -> Unit,
) {
    Text(
        text = "Fetched ${state.fetched}, ${state.withoutCaptions} had no captions",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "${state.transcribed} of ${state.totalVideos} transcribed · ${state.unitsSpent} units spent",
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
    if (state.stoppedForBudget) {
        Text(
            text =
                "Stopped to protect the daily quota. More tomorrow — " +
                    "transcripts cost 250 units each.",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
    Button(onClick = onFetchClick) { Text("Fetch more") }
}
