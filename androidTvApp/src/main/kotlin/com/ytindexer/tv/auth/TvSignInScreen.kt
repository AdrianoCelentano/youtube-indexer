package com.ytindexer.tv.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.ytindexer.shared.AppInfo
import com.ytindexer.ui.Dimens

/**
 * Sign-in surface for the TV app.
 *
 * Stateless so the screenshot tests can render every state without a ViewModel, a Google
 * account, or a second device.
 */
@Composable
internal fun TvSignInScreen(
    state: TvSignInUiState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Rendered only once signed in; kept as a slot so this screen stays stateless and the
    // screenshot tests can render every state without an indexer.
    signedInContent: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Dimens.TvOverscanPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = AppInfo.NAME, style = MaterialTheme.typography.displaySmall)

        when (state) {
            TvSignInUiState.Loading -> {
                Text(text = "Loading…", style = MaterialTheme.typography.bodyLarge)
            }

            TvSignInUiState.NotConfigured -> {
                NotConfiguredContent()
            }

            is TvSignInUiState.SignedOut -> {
                SignedOutContent(state, onSignInClick)
            }

            TvSignInUiState.RequestingCode -> {
                Text(text = "Requesting a sign-in code…", style = MaterialTheme.typography.bodyLarge)
            }

            is TvSignInUiState.AwaitingApproval -> {
                AwaitingApprovalContent(state)
            }

            TvSignInUiState.SignedIn -> {
                SignedInContent(onSignOutClick)
                signedInContent()
            }
        }
    }
}

@Composable
private fun NotConfiguredContent() {
    ErrorText(
        "No Google OAuth client ID/secret configured for the TV app.\n" +
            "Add googleOauthClientIdTv and googleOauthClientSecretTv to local.properties " +
            "and rebuild — see README.",
    )
}

@Composable
private fun ColumnScope.SignedOutContent(
    state: TvSignInUiState.SignedOut,
    onSignInClick: () -> Unit,
) {
    Text(
        text = "Connect your YouTube account to index your subscriptions.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    state.message?.let { ErrorText(it) }
    Button(onClick = onSignInClick) { Text("Sign in with Google") }
}

/**
 * The whole point of this screen: a short code and URL, sized to be readable from a
 * couch, that the user types in on a phone or laptop while this keeps polling in the
 * background.
 */
@Composable
private fun ColumnScope.AwaitingApprovalContent(state: TvSignInUiState.AwaitingApproval) {
    Text(
        text = "On your phone or computer, go to",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = state.verificationUrl,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "and enter this code:",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = state.userCode,
        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ColumnScope.SignedInContent(onSignOutClick: () -> Unit) {
    Text(text = "Signed in to YouTube.", style = MaterialTheme.typography.bodyLarge)
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
