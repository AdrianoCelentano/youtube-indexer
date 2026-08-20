package com.ytindexer.android.auth

import android.content.Intent
import com.ytindexer.shared.auth.OAuthTokens

/**
 * The browser authorization step, behind an interface so [SignInViewModel] can be tested
 * without a Google account, a browser, or an Android device.
 */
interface SignInFlow : AutoCloseable {
    /** Intent that opens the consent screen. */
    fun createSignInIntent(): Intent

    /** Turns the activity result into tokens, or throws an `AuthError`. */
    suspend fun handleResult(data: Intent?): OAuthTokens
}
