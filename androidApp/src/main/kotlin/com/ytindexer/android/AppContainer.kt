package com.ytindexer.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ytindexer.android.auth.GoogleAuthConfig
import com.ytindexer.android.auth.GoogleSignInClient
import com.ytindexer.android.auth.SignInViewModel
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.auth.createAuthManager

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
