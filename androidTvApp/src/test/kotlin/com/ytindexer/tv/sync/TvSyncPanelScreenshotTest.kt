package com.ytindexer.tv.sync

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.takahirom.roborazzi.captureRoboImage
import com.ytindexer.ui.sync.SyncUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvSyncPanelScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: SyncUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    TvSyncPanel(state = state, onSyncClick = {}, onClearClick = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/tv_sync_$name.png")
    }

    @Test
    fun idleEmpty() = capture("idle_empty", SyncUiState.Idle(indexedTotal = 0))

    @Test
    fun running() =
        capture(
            "running",
            SyncUiState.Running(videosIndexed = 150, channelsDone = 3, channelsTotal = 12, currentChannel = "x"),
        )

    @Test
    fun done() =
        capture(
            "done",
            SyncUiState.Done(videosIndexed = 214, channels = 12, indexedTotal = 214, sample = emptyList()),
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

/**
 * Robolectric renders against this API level.
 *
 * Deliberately 35 rather than the project's compileSdk of 36: Robolectric refuses SDK 36
 * unless the test JVM is Java 21 ("Android SDK 36 requires Java 21"), and this project
 * builds on Java 17. API 35 renders these screens identically.
 */
private const val ROBOLECTRIC_SDK = 35
