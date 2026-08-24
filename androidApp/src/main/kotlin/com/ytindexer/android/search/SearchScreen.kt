package com.ytindexer.android.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ytindexer.shared.search.SearchResult
import com.ytindexer.ui.Dimens

/**
 * Search over the local index.
 *
 * Stateless so every state can be rendered by the screenshot tests without a database.
 * [header] carries the sync and transcript panels, which belong above the results but are
 * not this screen's concern.
 */
@Composable
internal fun SearchScreen(
    state: SearchUiState,
    onPromptChange: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        item { header() }

        item {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = onPromptChange,
                label = { Text("Search your videos") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.categories.isNotEmpty()) {
            item { CategoryRow(state, onCategoryClick) }
        }

        item { ResultsSummary(state) }

        items(state.results, key = { it.video.id }) { result ->
            ResultRow(result, onClick = { onResultClick(result.video.id) })
            HorizontalDivider()
        }
    }
}

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
                label = { Text("${category.title} (${category.videoCount})") },
            )
        }
    }
}

@Composable
private fun ResultsSummary(state: SearchUiState) {
    val text =
        when {
            state.indexEmpty -> "Nothing indexed yet — run a sync first."
            state.searching -> "Searching…"
            state.results.isEmpty() && state.prompt.isNotBlank() -> "No matches for \"${state.prompt}\"."
            state.results.isEmpty() -> "No videos."
            state.prompt.isBlank() && state.selectedCategoryId == null -> "Most recent"
            else -> "${state.results.size} result${if (state.results.size == 1) "" else "s"}"
        }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun ResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Text(
            text = result.video.title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.video.publishedAt.take(DATE_LENGTH),
                style = MaterialTheme.typography.labelSmall,
            )
            matchLabel(result)?.let {
                // Showing where the hit came from explains why a result is here at all,
                // which matters most for transcript matches whose words appear nowhere
                // visible on the row.
                Text(text = it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun matchLabel(result: SearchResult): String? =
    when {
        result.matched.transcript -> "matched in transcript"
        result.matched.description -> "matched in description"
        result.matched.tags -> "matched in tags"
        else -> null
    }

private const val DATE_LENGTH = 10
