package com.ytindexer.shared

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AppInfoTest {

    @Test
    fun name_is_stable() {
        assertEquals("YouTube Indexer", AppInfo.NAME)
    }

    @Test
    fun greeting_mentions_the_surface_it_was_given() {
        assertContains(AppInfo.greeting("Mobile"), "Mobile")
        assertContains(AppInfo.greeting("TV"), "TV")
    }
}
