package com.ytindexer.android.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ytindexer.shared.AppInfo
import com.ytindexer.ui.Dimens

/**
 * Sign-in surface for the phone app.
 *
 * Stateless so the screenshot tests can render every state without a ViewModel or a
 * Google account.
 */
@Composable
internal fun SignInScreen(
    state: SignInUiState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Dimens.SpaceL),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)

        when (state) {
            SignInUiState.Loading -> {
                CircularProgressIndicator()
            }

            SignInUiState.Authorizing -> {
                CircularProgressIndicator()
                Text(text = "Waiting for Google…", style = MaterialTheme.typography.bodyMedium)
            }

            SignInUiState.NotConfigured -> {
                NotConfiguredContent()
            }

            is SignInUiState.SignedOut -> {
                SignedOutContent(state, onSignInClick)
            }

            is SignInUiState.SignedIn -> {
                SignedInContent(state, onSignOutClick)
            }
        }
    }
}

@Composable
private fun NotConfiguredContent() {
    ErrorText(
        "No Google OAuth client ID configured.\n" +
            "Add googleOauthClientIdAndroid to local.properties and rebuild — see README.",
    )
}

@Composable
private fun ColumnScope.SignedOutContent(
    state: SignInUiState.SignedOut,
    onSignInClick: () -> Unit,
) {
    Text(
        text = "Connect your YouTube account to index your videos.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    state.message?.let { ErrorText(it) }
    Button(onClick = onSignInClick) { Text("Sign in with Google") }
}

@Composable
private fun ColumnScope.SignedInContent(
    state: SignInUiState.SignedIn,
    onSignOutClick: () -> Unit,
) {
    Text(text = "Signed in to YouTube.", style = MaterialTheme.typography.bodyMedium)
    if (!state.grantedYouTubeScope) {
        ErrorText(
            "YouTube read access was not granted, so your videos cannot be indexed. " +
                "Sign in again and accept the YouTube permission.",
        )
    }
    OutlinedButton(onClick = onSignOutClick) { Text("Sign out") }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error,
    )
}
