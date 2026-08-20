package com.ytindexer.android.auth

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
 * Renders every sign-in state on the JVM. No emulator can run on this project's build
 * host (see README), so these goldens are the only automated check that the screen
 * actually looks right.
 *
 * Record: ./gradlew :androidApp:recordRoborazziDebug
 * Verify: ./gradlew :androidApp:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SignInScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        state: SignInUiState,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SignInScreen(state = state, onSignInClick = {}, onSignOutClick = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/sign_in_$name.png")
    }

    @Test
    fun signedOut() = capture("signed_out", SignInUiState.SignedOut())

    @Test
    fun signedOutWithError() =
        capture(
            "signed_out_error",
            SignInUiState.SignedOut("Network failure during token refresh"),
        )

    @Test
    fun signedIn() = capture("signed_in", SignInUiState.SignedIn(grantedYouTubeScope = true))

    @Test
    fun signedInWithoutYouTubeScope() =
        capture("signed_in_no_scope", SignInUiState.SignedIn(grantedYouTubeScope = false))

    @Test
    fun notConfigured() = capture("not_configured", SignInUiState.NotConfigured)
}

/**
 * Robolectric renders against this API level.
 *
 * Deliberately 35 rather than the project's compileSdk of 36: Robolectric refuses SDK 36
 * unless the test JVM is Java 21, and this project builds on Java 17.
 */
private const val ROBOLECTRIC_SDK = 35
