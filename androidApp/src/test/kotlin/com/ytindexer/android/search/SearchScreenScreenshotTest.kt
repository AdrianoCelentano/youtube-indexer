package com.ytindexer.android.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ytindexer.shared.index.CategoryWithCount
import com.ytindexer.shared.search.MatchFields
import com.ytindexer.shared.search.SearchResult
import com.ytindexer.shared.youtube.YouTubeVideo
import com.ytindexer.ui.search.SearchUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun result(
    id: String,
    title: String,
    published: String = "2026-03-14T00:00:00Z",
    matched: MatchFields = MatchFields(title = true, tags = false, description = false, transcript = false),
) = SearchResult(
    video = YouTubeVideo(id, title, "d", published, null, emptyList(), "28", 253),
    score = 25,
    matched = matched,
)

private val TRANSCRIPT_MATCH =
    MatchFields(title = false, tags = false, description = false, transcript = true)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SearchScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: SearchUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SearchScreen(
                        state = state,
                        onPromptChange = {},
                        onCategoryClick = {},
                        onResultClick = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/search_$name.png")
    }

    private val categories =
        listOf(
            CategoryWithCount("28", "Science & Technology", 120),
            CategoryWithCount("26", "Howto & Style", 44),
        )

    @Test
    fun emptyIndex() = capture("empty_index", SearchUiState(indexEmpty = true))

    @Test
    fun browsingRecent() =
        capture(
            "browse_recent",
            SearchUiState(
                categories = categories,
                results =
                    listOf(
                        result("v1", "Making sourdough at home"),
                        result("v2", "Kotlin Multiplatform in practice"),
                    ),
            ),
        )

    @Test
    fun promptResults() =
        capture(
            "prompt_results",
            SearchUiState(
                prompt = "sourdough",
                categories = categories,
                results =
                    listOf(
                        result("v1", "Making sourdough at home"),
                        // The case transcripts exist for: the word appears nowhere on the row.
                        result("v2", "Vlog 42 — a day in the kitchen", matched = TRANSCRIPT_MATCH),
                    ),
            ),
        )

    @Test
    fun categoryFiltered() =
        capture(
            "category_filtered",
            SearchUiState(
                prompt = "kotlin",
                selectedCategoryId = "28",
                categories = categories,
                results = listOf(result("v2", "Kotlin Multiplatform in practice")),
            ),
        )

    @Test
    fun noMatches() =
        capture(
            "no_matches",
            SearchUiState(prompt = "helicopter", categories = categories, results = emptyList()),
        )

    @Test
    fun searching() = capture("searching", SearchUiState(prompt = "sour", categories = categories, searching = true))
}
