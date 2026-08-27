package com.ytindexer.tv.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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

// thumbnailUrl is always null here: with one set, Coil would try a real network fetch
// during the test, making these screenshots slow, flaky and non-deterministic. null
// keeps VideoThumbnail rendering only its placeholder, which is what gets committed.
private fun result(
    id: String,
    title: String,
    channelTitle: String? = "Some Channel",
    published: String = "2026-03-14T00:00:00Z",
) = SearchResult(
    video = YouTubeVideo(id, title, "d", published, null, emptyList(), "28", 253, channelTitle = channelTitle),
    score = 25,
    matched = MatchFields(title = true, tags = false, description = false, transcript = false),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 1080p TV: 1920x1080 px at xhdpi (2x) == 960x540 dp. Android requires qualifiers in a
// strict order (size, then UI mode, then density) or parsing fails.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvSearchScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: SearchUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    TvSearchScreen(state = state, onCategoryClick = {}, onResultClick = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/tv_search_$name.png")
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
                        result("v3", "Debugging OAuth for three hours"),
                        result("v4", "A very long title that should wrap across two lines on the card"),
                    ),
            ),
        )

    @Test
    fun categoryFiltered() =
        capture(
            "category_filtered",
            SearchUiState(
                selectedCategoryId = "28",
                categories = categories,
                results = listOf(result("v2", "Kotlin Multiplatform in practice")),
            ),
        )
}

/**
 * Robolectric renders against this API level.
 *
 * Deliberately 35 rather than the project's compileSdk of 36: Robolectric refuses SDK 36
 * unless the test JVM is Java 21 ("Android SDK 36 requires Java 21"), and this project
 * builds on Java 17. API 35 renders these screens identically.
 */
private const val ROBOLECTRIC_SDK = 35
