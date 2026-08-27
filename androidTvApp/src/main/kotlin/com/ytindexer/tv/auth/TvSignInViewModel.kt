package com.ytindexer.tv.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytindexer.shared.auth.AuthError
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.Clock
import com.ytindexer.shared.auth.DeviceCodePollResult
import com.ytindexer.shared.auth.DeviceCodeSession
import com.ytindexer.shared.auth.GoogleDeviceCodeClient
import com.ytindexer.shared.auth.OAuthTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the TV sign-in screen should be showing. */
sealed interface TvSignInUiState {
    data object Loading : TvSignInUiState

    /** No client ID/secret pair was configured at build time. */
    data object NotConfigured : TvSignInUiState

    /** @param message non-null when a previous attempt failed. */
    data class SignedOut(
        val message: String? = null,
    ) : TvSignInUiState

    /** Between tapping "Sign in" and Google returning a code -- typically sub-second. */
    data object RequestingCode : TvSignInUiState

    /** The code is on screen; the user is expected to enter it on a phone or laptop. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUrl: String,
    ) : TvSignInUiState

    data object SignedIn : TvSignInUiState
}

/**
 * Drives device-code sign-in for the TV app.
 *
 * Owns the whole flow -- requesting a code, showing it, then polling until the user
 * finishes or the code dies -- the same way the phone app's `SignInViewModel` owns its
 * browser-redirect flow. The two aren't a shared abstraction because the flows genuinely
 * differ in shape: one waits for an activity result, the other polls on a timer.
 */
class TvSignInViewModel(
    private val authManager: AuthManager,
    private val deviceCodeClient: GoogleDeviceCodeClient,
    private val clock: Clock = Clock.System,
    // Injected rather than read from TvAuthConfig directly: BuildConfig carries real
    // credentials locally but empty ones on CI, which would otherwise make these tests
    // pass or fail depending on where they run.
    private val isConfigured: Boolean = TvAuthConfig.isConfigured,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TvSignInUiState>(TvSignInUiState.Loading)
    val uiState: StateFlow<TvSignInUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    private fun refreshState() {
        viewModelScope.launch {
            _uiState.value =
                when {
                    !isConfigured -> TvSignInUiState.NotConfigured
                    authManager.isSignedIn() -> TvSignInUiState.SignedIn
                    else -> TvSignInUiState.SignedOut()
                }
        }
    }

    fun signIn() {
        if (!isConfigured) return

        viewModelScope.launch {
            _uiState.value = TvSignInUiState.RequestingCode

            val session =
                try {
                    deviceCodeClient.requestCode(TvAuthConfig.requestedScopes)
                } catch (e: AuthError) {
                    _uiState.value = TvSignInUiState.SignedOut(e.message)
                    return@launch
                }

            _uiState.value = TvSignInUiState.AwaitingApproval(session.userCode, session.verificationUrl)
            pollUntilDone(session)
        }
    }

    /**
     * The give-up cases -- a network error, denied consent, the code expiring mid-poll,
     * or the code expiring before a poll ever ran -- all end the same way,
     * [TvSignInUiState.SignedOut] with a message, so they share the two `return` sites
     * below rather than each getting their own.
     */
    private suspend fun pollUntilDone(session: DeviceCodeSession) {
        var intervalMs = session.pollIntervalSeconds * MILLIS_PER_SECOND

        while (clock.nowEpochSeconds() < session.expiresAtEpochSeconds) {
            delay(intervalMs)

            val result =
                try {
                    deviceCodeClient.poll(session.deviceCode)
                } catch (e: AuthError) {
                    _uiState.value = TvSignInUiState.SignedOut(e.message)
                    return
                }

            val failureMessage =
                when (result) {
                    is DeviceCodePollResult.Authorized -> {
                        finishSignIn(result.tokens)
                        return
                    }

                    DeviceCodePollResult.Pending -> {
                        null
                    }

                    DeviceCodePollResult.SlowDown -> {
                        intervalMs += SLOW_DOWN_INCREMENT_MS
                        null
                    }

                    DeviceCodePollResult.AccessDenied -> {
                        "Sign-in was declined."
                    }

                    DeviceCodePollResult.Expired -> {
                        EXPIRED_MESSAGE
                    }
                }

            if (failureMessage != null) {
                _uiState.value = TvSignInUiState.SignedOut(failureMessage)
                return
            }
        }

        _uiState.value = TvSignInUiState.SignedOut(EXPIRED_MESSAGE)
    }

    private suspend fun finishSignIn(tokens: OAuthTokens) {
        if (tokens.refreshToken == null) {
            // Without a refresh token the session silently dies at the first expiry, so
            // treat it as a failure rather than a success -- same rule as the phone app.
            authManager.signOut()
            _uiState.value =
                TvSignInUiState.SignedOut("Google did not return a refresh token. Try signing in again.")
        } else {
            authManager.onSignedIn(tokens)
            _uiState.value = TvSignInUiState.SignedIn
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _uiState.value = TvSignInUiState.SignedOut()
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val SLOW_DOWN_INCREMENT_MS = 5_000L
        const val EXPIRED_MESSAGE = "Code expired before it was entered. Try again."
    }
}
