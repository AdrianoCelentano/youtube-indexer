package com.ytindexer.shared.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoDurationTest {
    @Test
    fun parses_hours_minutes_seconds() {
        assertEquals(3723L, parseIsoDurationSeconds("PT1H2M3S"))
    }

    @Test
    fun parses_partial_components() {
        assertEquals(253L, parseIsoDurationSeconds("PT4M13S"))
        assertEquals(42L, parseIsoDurationSeconds("PT42S"))
        assertEquals(7200L, parseIsoDurationSeconds("PT2H"))
    }

    @Test
    fun parses_day_component_from_long_livestream_archives() {
        assertEquals(90_061L, parseIsoDurationSeconds("P1DT1H1M1S"))
    }

    @Test
    fun zero_length_shorts_are_zero_not_null() {
        assertEquals(0L, parseIsoDurationSeconds("PT0S"))
    }

    @Test
    fun unparseable_values_are_null_rather_than_an_error() {
        // A video with an odd duration should still be indexed and searchable.
        assertNull(parseIsoDurationSeconds("banana"))
        assertNull(parseIsoDurationSeconds(""))
        assertNull(parseIsoDurationSeconds(null))
        assertNull(parseIsoDurationSeconds("PT"))
    }
}
