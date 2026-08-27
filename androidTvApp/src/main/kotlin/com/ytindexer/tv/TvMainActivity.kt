package com.ytindexer.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.ytindexer.tv.auth.TvSignInScreen
import com.ytindexer.tv.auth.TvSignInViewModel
import com.ytindexer.tv.search.TvSearchScreen
import com.ytindexer.tv.search.openVideo
import com.ytindexer.tv.sync.TvSyncPanel
import com.ytindexer.ui.search.SearchViewModel
import com.ytindexer.ui.sync.SyncUiState
import com.ytindexer.ui.sync.SyncViewModel

class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = TvAppContainer(this)

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
private fun SignInRoute(container: TvAppContainer) {
    val signInViewModel: TvSignInViewModel = viewModel(factory = container.signInViewModelFactory())
    val signInState by signInViewModel.uiState.collectAsState()

    val syncViewModel: SyncViewModel = viewModel(factory = container.syncViewModelFactory())
    val syncState by syncViewModel.uiState.collectAsState()

    val searchViewModel: SearchViewModel = viewModel(factory = container.searchViewModelFactory())
    val searchState by searchViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // A completed sync changes both the results and the category list.
    LaunchedEffect(syncState) {
        if (syncState is SyncUiState.Done) searchViewModel.onIndexChanged()
    }

    TvSignInScreen(
        state = signInState,
        onSignInClick = signInViewModel::signIn,
        onSignOutClick = signInViewModel::signOut,
        signedInContent = {
            TvSearchScreen(
                state = searchState,
                onCategoryClick = searchViewModel::onCategoryClick,
                onResultClick = { videoId -> openVideo(context, videoId) },
                header = {
                    TvSyncPanel(
                        state = syncState,
                        onSyncClick = syncViewModel::sync,
                        onClearClick = syncViewModel::clearIndex,
                    )
                },
            )
        },
    )
}
