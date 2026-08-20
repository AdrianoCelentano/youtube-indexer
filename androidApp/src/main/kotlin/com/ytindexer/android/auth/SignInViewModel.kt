package com.ytindexer.android.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytindexer.shared.auth.AuthError
import com.ytindexer.shared.auth.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sign-in screen should be showing. */
sealed interface SignInUiState {
    data object Loading : SignInUiState

    /** @param message non-null when a previous attempt failed. */
    data class SignedOut(
        val message: String? = null,
    ) : SignInUiState

    data object Authorizing : SignInUiState

    data class SignedIn(
        val grantedYouTubeScope: Boolean,
    ) : SignInUiState

    /** No client ID was configured at build time. */
    data object NotConfigured : SignInUiState
}

class SignInViewModel(
    private val authManager: AuthManager,
    private val signInClient: SignInFlow,
    // Injected rather than read from GoogleAuthConfig directly: BuildConfig carries a
    // real client ID locally but an empty one on CI, which would otherwise make these
    // tests pass or fail depending on where they run.
    private val isConfigured: Boolean = GoogleAuthConfig.isConfigured,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Loading)
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    private fun refreshState() {
        viewModelScope.launch {
            _uiState.value =
                when {
                    !isConfigured -> SignInUiState.NotConfigured
                    authManager.isSignedIn() -> SignInUiState.SignedIn(grantedYouTubeScope = true)
                    else -> SignInUiState.SignedOut()
                }
        }
    }

    /** Builds the consent-screen intent, or null if the app has no client ID. */
    fun signInIntent(): Intent? =
        if (isConfigured) {
            _uiState.value = SignInUiState.Authorizing
            signInClient.createSignInIntent()
        } else {
            null
        }

    /** Called with the activity result from the consent screen. */
    fun onSignInResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value =
                try {
                    val tokens = signInClient.handleResult(data)

                    if (tokens.refreshToken == null) {
                        // Without a refresh token the session silently dies at the first
                        // expiry, so treat it as a failure rather than a success.
                        authManager.signOut()
                        SignInUiState.SignedOut(
                            "Google did not return a refresh token. " +
                                "Check that access_type=offline and prompt=consent are set.",
                        )
                    } else {
                        authManager.onSignedIn(tokens)
                        SignInUiState.SignedIn(
                            grantedYouTubeScope =
                                tokens.scopes.isEmpty() ||
                                    tokens.hasScope(GoogleAuthConfig.YOUTUBE_READONLY_SCOPE),
                        )
                    }
                } catch (
                    @Suppress("SwallowedException") e: AuthError.NotSignedIn,
                ) {
                    // Deliberately swallowed: the user cancelled or declined consent.
                    // That is a normal outcome, not something to report back to them.
                    SignInUiState.SignedOut()
                } catch (e: AuthError) {
                    SignInUiState.SignedOut(e.message)
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _uiState.value = SignInUiState.SignedOut()
        }
    }

    override fun onCleared() {
        signInClient.close()
        super.onCleared()
    }
}
