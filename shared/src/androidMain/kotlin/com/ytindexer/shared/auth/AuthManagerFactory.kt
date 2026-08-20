package com.ytindexer.shared.auth

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the Android auth stack.
 *
 * Exists so app modules don't have to know that tokens are fetched with Ktor or stored in
 * EncryptedSharedPreferences -- both stay implementation details of `:shared`, which also
 * keeps those dependencies off the app modules' classpaths.
 *
 * @param clientId the Google OAuth client ID, supplied by the app from its build config.
 */
fun createAuthManager(
    context: Context,
    clientId: String,
    httpClient: HttpClient = defaultHttpClient(),
): AuthManager =
    AuthManager(
        tokenStore = EncryptedTokenStore(context),
        refresher =
            GoogleTokenRefresher(
                httpClient = httpClient,
                clientId = clientId,
                clock = Clock.System,
            ),
    )

private fun defaultHttpClient(): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            // Google adds response fields over time; unknown keys must not break parsing.
            json(Json { ignoreUnknownKeys = true })
        }
    }
