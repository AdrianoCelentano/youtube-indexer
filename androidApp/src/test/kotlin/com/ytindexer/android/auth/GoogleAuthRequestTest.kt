package com.ytindexer.android.auth

import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.ResponseTypeValues
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEST_CLIENT_ID = "595648951463-example.apps.googleusercontent.com"
private const val TEST_REDIRECT = "com.googleusercontent.apps.595648951463-example:/oauth2redirect"

/**
 * Builds the **real** AppAuth authorization request.
 *
 * The ViewModel tests use a fake [SignInFlow], so they never touch AppAuth and cannot
 * catch its input validation. That gap shipped a crash: `prompt` was passed through
 * `setAdditionalParameters`, and AppAuth rejects any parameter it models first-class with
 * `IllegalArgumentException: Parameter prompt is directly supported via the authorization
 * request builder`. The app died the instant the user tapped sign-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleAuthRequestTest {
    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest
            .Builder(
                GoogleAuthConfig.serviceConfig,
                TEST_CLIENT_ID,
                ResponseTypeValues.CODE,
                Uri.parse(TEST_REDIRECT),
            ).setScopes(GoogleAuthConfig.requestedScopes)
            .setPrompt(GoogleAuthConfig.PROMPT)
            .setAdditionalParameters(GoogleAuthConfig.authRequestParams)
            .build()

    @Test
    fun request_builds_without_throwing() {
        // Regression: this threw IllegalArgumentException and crashed the app on tap.
        assertNotNull(buildRequest())
    }

    @Test
    fun additional_parameters_contain_no_first_class_appauth_parameters() {
        // AppAuth throws for any of these if passed as an additional parameter.
        val reserved = setOf("prompt", "scope", "response_type", "redirect_uri", "client_id", "state")
        val offenders = GoogleAuthConfig.authRequestParams.keys.intersect(reserved)

        assertTrue(offenders.isEmpty(), "these must use builder methods instead: $offenders")
    }

    @Test
    fun requests_offline_access_so_google_returns_a_refresh_token() {
        assertEquals("offline", buildRequest().additionalParameters["access_type"])
    }

    @Test
    fun forces_the_consent_screen_so_repeat_sign_ins_still_yield_a_refresh_token() {
        assertEquals("consent", buildRequest().prompt)
    }

    @Test
    fun uses_pkce_since_this_is_a_public_client_with_no_secret() {
        val request = buildRequest()

        assertNotNull(request.codeVerifier, "PKCE is what proves the exchange came from this app")
        assertEquals("S256", request.codeVerifierChallengeMethod)
    }

    @Test
    fun asks_for_the_youtube_readonly_scope() {
        assertTrue(
            buildRequest().scope.orEmpty().contains(GoogleAuthConfig.YOUTUBE_READONLY_SCOPE),
        )
    }

    @Test
    fun does_not_ask_for_write_capable_scopes() {
        // force-ssl was dropped with transcripts: captions.download needs edit permission
        // on the video, so it cannot work for subscribed channels. Requesting an unused
        // write scope is what verification review rejects.
        assertFalse(buildRequest().scope.orEmpty().contains("force-ssl"))
    }
}
