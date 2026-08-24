package com.ytindexer.android.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ytindexer.android.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Exercises what happens on first launch after a fresh install: no stored tokens, an
 * empty database, and the Keystore-backed token store being created for the very first
 * time.
 *
 * An update skips all of that -- the key and database already exist -- which is why a
 * fault here would only ever be seen by new installs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreshInstallLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun auth_state_can_be_read_before_anyone_has_signed_in() =
        runBlocking {
            val container = AppContainer(context)

            // First touch of EncryptedSharedPreferences creates the master key.
            assertFalse(container.authManager.isSignedIn())
            assertFalse(container.authManager.hasGrantedScope("any-scope"))
        }

    @Test
    fun the_index_can_be_read_and_searched_while_empty() =
        runBlocking {
            val container = AppContainer(context)
            val indexing = container.indexingForTest()

            assertEquals(0L, indexing.store.videoCount())
            assertEquals(emptyList(), indexing.store.populatedCategories())
            assertEquals(emptyList(), indexing.searchEngine.search(""))
            assertEquals(emptyList(), indexing.searchEngine.search("sourdough"))
            assertEquals(0, indexing.quotaLedger.usedToday().toInt())
        }
}
