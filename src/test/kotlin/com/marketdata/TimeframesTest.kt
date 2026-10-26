package com.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeframesTest {

    @Test
    fun `supported timeframe order is canonical`() {
        assertEquals(listOf("1m", "5m", "15m", "1h", "4h", "1d"), Timeframes.ordered)
    }

    @Test
    fun `normalizes comma separated values and removes duplicates`() {
        assertEquals(
            listOf("1m", "5m", "1d"),
            Timeframes.normalize(listOf("1M, 5m", "1d", "1m"))
        )
    }

    @Test
    fun `rejects unsupported timeframes`() {
        assertNull(Timeframes.normalize(listOf("30m")))
        assertNull(Timeframes.normalize(listOf("1w")))
        assertNull(Timeframes.normalize(listOf("1m,unknown")))
        assertNull(Timeframes.normalize(emptyList()))
    }

    @Test
    fun `isAllowed is case insensitive`() {
        assertTrue(Timeframes.isAllowed("1M"))
        assertTrue(Timeframes.isAllowed("4h"))
        assertFalse(Timeframes.isAllowed("30m"))
    }

    @Test
    fun `sorts available timeframes canonically`() {
        assertEquals(
            listOf("1m", "15m", "1h", "1d"),
            Timeframes.sortAvailable(listOf("1d", "1h", "15m", "1m"))
        )
        assertEquals(
            listOf("1m", "5m", "30m"),
            Timeframes.sortAvailable(listOf("30m", "5m", "1m"))
        )
    }
}
