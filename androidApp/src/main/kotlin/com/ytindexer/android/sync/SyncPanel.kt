package com.ytindexer.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ytindexer.shared.youtube.YouTubeVideo
import com.ytindexer.ui.Dimens
import com.ytindexer.ui.sync.SyncUiState

/**
 * Sync controls and results.
 *
 * The completed state deliberately shows a few real videos with their mapped fields.
 * Nothing in this project has yet been checked against a real YouTube response, so this
 * is how the mapping gets eyeballed rather than assumed.
 */
@Composable
internal fun SyncPanel(
    state: SyncUiState,
    onSyncClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider()

        when (state) {
            is SyncUiState.Idle -> {
                Text(
                    text = indexedLabel(state.indexedTotal),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSyncClick) { Text("Sync subscriptions") }
            }

            is SyncUiState.Running -> {
                CircularProgressIndicator()
                Text(
                    text = syncProgressText(state),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is SyncUiState.Done -> {
                Text(
                    text =
                        "Indexed ${state.videosIndexed} videos from ${state.channels} channels " +
                            "(${state.indexedTotal} stored)",
                    style = MaterialTheme.typography.titleSmall,
                )
                SampleVideos(state.sample)
                SyncActions(onSyncClick, onClearClick)
            }

            is SyncUiState.QuotaExhausted -> {
                Text(
                    text =
                        "Daily API quota used up after ${state.videosIndexed} videos. " +
                            "Progress is saved — the next sync resumes where this stopped. " +
                            "Quota resets at midnight Pacific.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                SyncActions(onSyncClick, onClearClick)
            }

            is SyncUiState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = indexedLabel(state.indexedTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
                SyncActions(onSyncClick, onClearClick)
            }
        }
    }
}

@Composable
private fun SyncActions(
    onSyncClick: () -> Unit,
    onClearClick: () -> Unit,
) {
    Button(onClick = onSyncClick) { Text("Sync again") }
    TextButton(onClick = onClearClick) { Text("Clear index") }
}

@Composable
private fun SampleVideos(sample: List<YouTubeVideo>) {
    if (sample.isEmpty()) return

    Text(
        text = "Most recent:",
        style = MaterialTheme.typography.labelMedium,
    )
    sample.forEach { video ->
        Text(
            text = video.title.ifBlank { "(no title — mapping problem)" },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = videoDetail(video),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shows the fields most likely to reveal a mapping error against real data. */
private fun videoDetail(video: YouTubeVideo): String {
    val duration = video.durationSeconds?.let { formatDuration(it) } ?: "no duration"
    val category = video.categoryId?.let { "cat $it" } ?: "no category"
    val description = if (video.description.isBlank()) "no description" else "${video.description.length} chars"
    return "$duration · $category · ${video.tags.size} tags · $description"
}

private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun syncProgressText(state: SyncUiState.Running): String =
    if (state.channelsTotal == 0) {
        "Finding your subscriptions…"
    } else {
        "${state.channelsDone}/${state.channelsTotal} channels · ${state.videosIndexed} videos\n${state.currentChannel}"
    }

private fun indexedLabel(total: Long): String = if (total == 0L) "No videos indexed yet." else "$total videos indexed."

private const val SECONDS_PER_MINUTE = 60L
