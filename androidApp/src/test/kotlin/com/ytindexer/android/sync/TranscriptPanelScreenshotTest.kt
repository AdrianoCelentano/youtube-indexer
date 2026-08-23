package com.ytindexer.android.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class TranscriptPanelScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: TranscriptUiState,
    ) {
        composeRule.setContent {
            MaterialTheme { Surface { TranscriptPanel(state = state, onFetchClick = {}) } }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/transcript_$name.png")
    }

    @Test
    fun needsReauthorisation() =
        // The state an existing user hits first: their grant predates force-ssl.
        capture("needs_reauth", TranscriptUiState.NeedsReauthorisation)

    @Test
    fun idle() =
        capture(
            "idle",
            TranscriptUiState.Idle(
                transcribed = 12,
                totalVideos = 214,
                affordableToday = 32,
                quotaUsedToday = 40,
            ),
        )

    @Test
    fun idleOutOfQuota() =
        capture(
            "idle_no_quota",
            TranscriptUiState.Idle(
                transcribed = 44,
                totalVideos = 214,
                affordableToday = 0,
                quotaUsedToday = 9_800,
            ),
        )

    @Test
    fun done() =
        capture(
            "done",
            TranscriptUiState.Done(
                fetched = 8,
                withoutCaptions = 2,
                unitsSpent = 2_100,
                stoppedForBudget = true,
                transcribed = 20,
                totalVideos = 214,
            ),
        )

    @Test
    fun failed() = capture("failed", TranscriptUiState.Failed("Network failure calling captions"))
}
