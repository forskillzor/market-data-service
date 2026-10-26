package com.marketdata

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BatchFootprintSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes mixed batch request with defaults`() {
        val request = json.decodeFromString(
            BatchFootprintRequest.serializer(),
            """
            {
              "exchange": "Binance",
              "symbol": "BTCUSDT",
              "queries": [
                {"timeframe": "1m", "start": 1000, "end": 2000},
                {"timeframe": "1h", "start": 0, "end": 3600000, "limit": 10, "order": "asc"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("Binance", request.exchange)
        assertEquals("BTCUSDT", request.symbol)
        assertEquals(2, request.queries.size)
        assertEquals(20, request.queries[0].limit)
        assertEquals("desc", request.queries[0].order)
        assertEquals(1000L, request.queries[0].start)
        assertEquals(2000L, request.queries[0].end)
        assertEquals(10, request.queries[1].limit)
        assertEquals("asc", request.queries[1].order)
    }

    @Test
    fun `encodes grouped response`() {
        val response = BatchFootprintResponse(
            exchange = "Binance",
            symbol = "BTCUSDT",
            results = listOf(
                BatchFootprintResult(
                    timeframe = "1m",
                    candles = emptyList()
                )
            )
        )

        val encoded = json.encodeToString(BatchFootprintResponse.serializer(), response)
        val decoded = json.decodeFromString(BatchFootprintResponse.serializer(), encoded)

        assertEquals(response, decoded)
    }
}
