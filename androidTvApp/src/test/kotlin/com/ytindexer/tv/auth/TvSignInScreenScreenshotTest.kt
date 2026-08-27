package com.ytindexer.tv.auth

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
 * Renders the TV sign-in screen at 1080p on the JVM and writes a PNG.
 *
 * Record/update goldens: ./gradlew :androidTvApp:recordRoborazziDebug
 * Verify against goldens: ./gradlew :androidTvApp:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvSignInScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: TvSignInUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    TvSignInScreen(state = state, onSignInClick = {}, onSignOutClick = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/tv_sign_in_$name.png")
    }

    @Test
    fun notConfigured() = capture("not_configured", TvSignInUiState.NotConfigured)

    @Test
    fun signedOut() = capture("signed_out", TvSignInUiState.SignedOut())

    @Test
    fun signedOutError() = capture("signed_out_error", TvSignInUiState.SignedOut("Sign-in was declined."))

    @Test
    fun requestingCode() = capture("requesting_code", TvSignInUiState.RequestingCode)

    @Test
    fun awaitingApproval() =
        capture(
            "awaiting_approval",
            TvSignInUiState.AwaitingApproval(userCode = "ABCD-WXYZ", verificationUrl = "google.com/device"),
        )

    @Test
    fun signedIn() = capture("signed_in", TvSignInUiState.SignedIn)
}

/**
 * Robolectric renders against this API level.
 *
 * Deliberately 35 rather than the project's compileSdk of 36: Robolectric refuses SDK 36
 * unless the test JVM is Java 21 ("Android SDK 36 requires Java 21"), and this project
 * builds on Java 17. API 35 renders these screens identically.
 */
private const val ROBOLECTRIC_SDK = 35
