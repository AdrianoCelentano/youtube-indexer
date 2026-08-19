package com.ytindexer.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the TV screen at 1080p on the JVM and writes a PNG.
 *
 * This is the only automated way to see the 10-foot UI in this project: no emulator can
 * run on the build host, and Waydroid only provides phone/tablet Android images with no
 * leanback surface -- see README.
 *
 * Record/update goldens: ./gradlew :androidTvApp:recordRoborazziDebug
 * Verify against goldens: ./gradlew :androidTvApp:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 1080p TV: 1920x1080 px at xhdpi (2x) == 960x540 dp. Android requires qualifiers in a
// strict order (size, then UI mode, then density) or parsing fails.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvScaffoldingScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvScaffoldingScreen() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    TvScaffoldingScreen()
                }
            }
        }

        // Explicit path so goldens live in source control and CI can diff against them.
        composeRule.onRoot().captureRoboImage("src/test/screenshots/tv_scaffolding_screen.png")
    }
}

/**
 * Robolectric renders against this API level.
 *
 * Deliberately 35 rather than the project's compileSdk of 36: Robolectric refuses SDK 36
 * unless the test JVM is Java 21 ("Android SDK 36 requires Java 21"), and this project
 * builds on Java 17. API 35 renders these screens identically.
 */
private const val ROBOLECTRIC_SDK = 35
