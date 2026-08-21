package com.ytindexer.android.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ytindexer.shared.youtube.YouTubeVideo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun video(
    id: String,
    title: String,
    duration: Long? = 253,
    category: String? = "28",
    tags: List<String> = listOf("a", "b"),
    description: String = "some description text",
) = YouTubeVideo(id, title, description, "2026-01-01T00:00:00Z", null, tags, category, duration)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SyncPanelScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: SyncUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SyncPanel(state = state, onSyncClick = {}, onClearClick = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/sync_$name.png")
    }

    @Test
    fun idleEmpty() = capture("idle_empty", SyncUiState.Idle(indexedTotal = 0))

    @Test
    fun running() = capture("running", SyncUiState.Running(videosIndexed = 150, pages = 3))

    @Test
    fun done() =
        capture(
            "done",
            SyncUiState.Done(
                videosIndexed = 214,
                indexedTotal = 214,
                sample =
                    listOf(
                        video("v1", "How I built a Kotlin Multiplatform app"),
                        video("v2", "Debugging OAuth for three hours", duration = 1_845),
                        video("v3", "Short clip", duration = 41, tags = emptyList()),
                    ),
            ),
        )

    @Test
    fun doneWithSuspiciousMapping() =
        // If real data renders like this, the DTO mapping is wrong -- that is the point
        // of showing these fields at all.
        capture(
            "done_bad_mapping",
            SyncUiState.Done(
                videosIndexed = 2,
                indexedTotal = 2,
                sample =
                    listOf(
                        video("v1", "", duration = null, category = null, tags = emptyList(), description = ""),
                    ),
            ),
        )

    @Test
    fun quotaExhausted() = capture("quota", SyncUiState.QuotaExhausted(videosIndexed = 500, indexedTotal = 500))

    @Test
    fun failed() =
        capture(
            "failed",
            SyncUiState.Failed("Network failure calling playlistItems", videosIndexed = 50, indexedTotal = 50),
        )
}
