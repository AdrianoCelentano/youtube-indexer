package com.ytindexer.tv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.createAuthManager
import com.ytindexer.shared.auth.createDeviceCodeClient
import com.ytindexer.shared.index.IndexingComponent
import com.ytindexer.shared.index.createIndexingComponent
import com.ytindexer.tv.auth.TvAuthConfig
import com.ytindexer.tv.auth.TvSignInViewModel
import com.ytindexer.ui.search.SearchViewModel
import com.ytindexer.ui.sync.SyncViewModel

/**
 * Manual dependency wiring for the TV app.
 *
 * Deliberately not shared with `androidApp.AppContainer`: the two apps' auth stacks
 * differ (device-code vs browser-redirect), so the container itself has to differ even
 * though most of what it wires -- indexing, search, sync -- is identical.
 */
class TvAppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val authManager: AuthManager by lazy {
        createAuthManager(appContext, TvAuthConfig.clientId, TvAuthConfig.clientSecret)
    }

    private val indexing: IndexingComponent by lazy {
        createIndexingComponent(appContext, authManager)
    }

    fun signInViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TvSignInViewModel(
                    authManager = authManager,
                    deviceCodeClient = createDeviceCodeClient(TvAuthConfig.clientId, TvAuthConfig.clientSecret),
                ) as T
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
}
