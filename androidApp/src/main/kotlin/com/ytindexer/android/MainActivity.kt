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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ytindexer.android.auth.SignInScreen
import com.ytindexer.android.auth.SignInViewModel
import com.ytindexer.android.search.SearchScreen
import com.ytindexer.android.search.SearchViewModel
import com.ytindexer.android.search.openVideo
import com.ytindexer.android.sync.SyncPanel
import com.ytindexer.android.sync.SyncUiState
import com.ytindexer.android.sync.SyncViewModel
import com.ytindexer.android.sync.TranscriptPanel
import com.ytindexer.android.sync.TranscriptViewModel

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

    val syncViewModel: SyncViewModel = viewModel(factory = container.syncViewModelFactory())
    val syncState by syncViewModel.uiState.collectAsState()

    val transcriptViewModel: TranscriptViewModel =
        viewModel(factory = container.transcriptViewModelFactory())
    val transcriptState by transcriptViewModel.uiState.collectAsState()

    val searchViewModel: SearchViewModel = viewModel(factory = container.searchViewModelFactory())
    val searchState by searchViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // A completed sync changes both the results and the category list.
    LaunchedEffect(syncState) {
        if (syncState is SyncUiState.Done) searchViewModel.onIndexChanged()
    }

    SignInScreen(
        state = state,
        onSignInClick = { viewModel.signInIntent()?.let(launcher::launch) },
        onSignOutClick = viewModel::signOut,
        signedInContent = {
            SearchScreen(
                state = searchState,
                onPromptChange = searchViewModel::onPromptChange,
                onCategoryClick = searchViewModel::onCategoryClick,
                onResultClick = { videoId -> openVideo(context, videoId) },
                header = {
                    SyncPanel(
                        state = syncState,
                        onSyncClick = syncViewModel::sync,
                        onClearClick = syncViewModel::clearIndex,
                    )
                    TranscriptPanel(
                        state = transcriptState,
                        onFetchClick = transcriptViewModel::fetchTranscripts,
                    )
                },
            )
        },
    )
}
