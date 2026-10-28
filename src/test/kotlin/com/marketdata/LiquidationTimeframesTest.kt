package com.marketdata

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiquidationTimeframesTest {

    @Test
    fun `supports all liquidation chart timeframes`() {
        assertEquals(
            listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"),
            LiquidationTimeframes.ordered
        )
        assertEquals(1_800_000L, LiquidationTimeframes.durationMs("30m"))
        assertEquals(604_800_000L, LiquidationTimeframes.durationMs("1w"))
    }

    @Test
    fun `normalizes default and rejects unknown timeframes`() {
        assertEquals("1m", LiquidationTimeframes.normalize(null))
        assertEquals("15m", LiquidationTimeframes.normalize("15M"))
        assertNull(LiquidationTimeframes.normalize("2h"))
    }

    @Test
    fun `aligns buckets to absolute time`() {
        val timestamp = Instant.parse("2026-10-28T15:47:31Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-28T15:45:00Z").toEpochMilli(), LiquidationTimeframes.bucketStart(timestamp, "15m"))
        assertEquals(Instant.parse("2026-10-28T15:00:00Z").toEpochMilli(), LiquidationTimeframes.bucketStart(timestamp, "1h"))
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), LiquidationTimeframes.bucketStart(timestamp, "1d"))
    }

    @Test
    fun `aligns weekly buckets to monday utc`() {
        val sunday = Instant.parse("2026-10-25T23:59:59Z").toEpochMilli()
        val monday = Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()
        assertEquals(monday, LiquidationTimeframes.bucketStart(sunday, "1w"))
    }
}
