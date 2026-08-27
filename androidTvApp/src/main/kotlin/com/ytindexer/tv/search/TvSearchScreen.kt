package com.ytindexer.tv.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Text
import com.ytindexer.shared.search.SearchResult
import com.ytindexer.ui.Dimens
import com.ytindexer.ui.VideoThumbnail
import com.ytindexer.ui.search.SearchUiState

/**
 * Search over the local index, for the TV surface.
 *
 * A card grid rather than the phone app's list: at 10-foot viewing distance a thumbnail
 * is what lets someone tell videos apart at a glance, and a grid uses the extra width a
 * TV has that a phone doesn't.
 *
 * There is deliberately no text field here. A software keyboard driven by D-pad is slow
 * enough that browsing by category is the primary path on TV; free-text search on this
 * surface is left for when voice input (the natural TV equivalent) lands.
 *
 * Stateless so every state can be rendered by the screenshot tests without a database.
 * [header] carries the sync panel, which belongs above the results but is not this
 * screen's concern.
 */
@Composable
internal fun TvSearchScreen(
    state: SearchUiState,
    onCategoryClick: (String) -> Unit,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Dimens.TvOverscanPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        header()

        if (state.categories.isNotEmpty()) {
            CategoryRow(state, onCategoryClick)
        }

        ResultsSummary(state)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = CardWidth),
            contentPadding = PaddingValues(vertical = Dimens.SpaceS),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.results, key = { it.video.id }) { result ->
                ResultCard(result, onClick = { onResultClick(result.video.id) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryRow(
    state: SearchUiState,
    onCategoryClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        state.categories.forEach { category ->
            FilterChip(
                selected = state.selectedCategoryId == category.categoryId,
                onClick = { onCategoryClick(category.categoryId) },
            ) {
                Text("${category.title} (${category.videoCount})")
            }
        }
    }
}

@Composable
private fun ResultsSummary(state: SearchUiState) {
    val text =
        when {
            state.indexEmpty -> "Nothing indexed yet — sign in and sync first."
            state.results.isEmpty() -> "No videos."
            state.selectedCategoryId == null -> "Most recent"
            else -> "${state.results.size} video${if (state.results.size == 1) "" else "s"}"
        }

    Text(text = text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ResultCard(
    result: SearchResult,
    onClick: () -> Unit,
) {
    StandardCardContainer(
        modifier = Modifier.width(CardWidth),
        imageCard = { interactionSource ->
            Card(onClick = onClick, interactionSource = interactionSource) {
                VideoThumbnail(
                    url = result.video.thumbnailUrl,
                    contentDescription = result.video.title,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        title = {
            Text(
                text = result.video.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = TITLE_MAX_LINES,
            )
        },
        subtitle = {
            Text(text = result.video.channelTitle ?: publishedDate(result), style = MaterialTheme.typography.labelSmall)
        },
    )
}

private fun publishedDate(result: SearchResult) = result.video.publishedAt.take(DATE_LENGTH)

private val CardWidth = 200.dp
private const val TITLE_MAX_LINES = 2
private const val DATE_LENGTH = 10
