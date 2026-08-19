package com.ytindexer.android

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

/**
 * Renders the phone screen on the JVM and writes a PNG.
 *
 * This is how UI is verified in this project: no emulator can run on the build host
 * (no nested virtualisation for KVM, and cross-arch guests are refused) -- see README.
 *
 * Record/update goldens: ./gradlew :androidApp:recordRoborazziDebug
 * Verify against goldens: ./gradlew :androidApp:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class MobileScaffoldingScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mobileScaffoldingScreen() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MobileScaffoldingScreen()
                }
            }
        }

        // Explicit path so goldens live in source control and CI can diff against them.
        composeRule.onRoot().captureRoboImage("src/test/screenshots/mobile_scaffolding_screen.png")
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
