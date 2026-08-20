package com.ytindexer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ytindexer.android.auth.SignInScreen
import com.ytindexer.android.auth.SignInViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SignInRoute(container)
                }
            }
        }
    }
}

@Composable
private fun SignInRoute(container: AppContainer) {
    val viewModel: SignInViewModel = viewModel(factory = container.signInViewModelFactory())
    val state by viewModel.uiState.collectAsState()

    // AppAuth returns its result via the activity result, including the cancelled case
    // where data is null.
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onSignInResult(result.data)
        }

    SignInScreen(
        state = state,
        onSignInClick = { viewModel.signInIntent()?.let(launcher::launch) },
        onSignOutClick = viewModel::signOut,
    )
}
