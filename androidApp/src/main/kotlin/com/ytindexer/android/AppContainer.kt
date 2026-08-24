package com.ytindexer.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ytindexer.android.auth.GoogleAuthConfig
import com.ytindexer.android.auth.GoogleSignInClient
import com.ytindexer.android.auth.SignInViewModel
import com.ytindexer.android.search.SearchViewModel
import com.ytindexer.android.sync.SyncViewModel
import com.ytindexer.android.sync.TranscriptViewModel
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.createAuthManager
import com.ytindexer.shared.index.IndexingComponent
import com.ytindexer.shared.index.createIndexingComponent

/**
 * Manual dependency wiring.
 *
 * Deliberately not a DI framework yet: the graph is three objects. Koin is already in the
 * version catalog for when the indexing and search layers arrive and this stops being
 * trivial.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    // How tokens are fetched and persisted is :shared's business, not the app's.
    val authManager: AuthManager by lazy {
        createAuthManager(appContext, GoogleAuthConfig.clientId)
    }

    // How videos are fetched and stored is :shared's business too.
    private val indexing: IndexingComponent by lazy {
        createIndexingComponent(appContext, authManager)
    }

    fun syncViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SyncViewModel(indexing.indexer, indexing.store) as T
        }

    fun searchViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(indexing.searchEngine, indexing.store) as T
        }

    fun transcriptViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TranscriptViewModel(
                    backfiller = indexing.backfiller,
                    store = indexing.store,
                    ledger = indexing.quotaLedger,
                    authManager = authManager,
                ) as T
        }

    fun signInViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SignInViewModel(
                    authManager = authManager,
                    signInClient = GoogleSignInClient(appContext),
                ) as T
        }
}
