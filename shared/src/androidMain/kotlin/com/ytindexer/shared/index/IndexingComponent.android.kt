package com.ytindexer.shared.index

import android.content.Context
import com.ytindexer.shared.auth.AuthManager
import com.ytindexer.shared.youtube.YouTubeApiClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * Everything needed to index and read the local catalogue, assembled in one place.
 *
 * Built here rather than in the app modules so SQLDelight and Ktor stay implementation
 * details of `:shared`.
 */
class IndexingComponent internal constructor(
    val indexer: VideoIndexer,
    val store: VideoIndexStore,
)

fun createIndexingComponent(
    context: Context,
    authManager: AuthManager,
): IndexingComponent {
    val database = createDatabase(context)
    val store = VideoIndexStore(database, Dispatchers.IO)

    val httpClient =
        HttpClient {
            install(ContentNegotiation) {
                // Google adds response fields over time; unknown keys must not break parsing.
                json(Json { ignoreUnknownKeys = true })
            }
        }

    return IndexingComponent(
        indexer =
            VideoIndexer(
                api = YouTubeApiClient(httpClient, authManager),
                store = store,
                database = database,
                ioDispatcher = Dispatchers.IO,
            ),
        store = store,
    )
}
