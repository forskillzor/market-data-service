package com.marketdata

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchFootprintEndpointTest {

    private fun withBatchServer(
        queryFootprint: (AggregatesQuery) -> List<FootprintResponse> = { emptyList() },
        block: (port: Int) -> Unit
    ) {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                batchFootprintRoute(queryFootprint)
            }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(0, 0)
        }
    }

    private fun post(port: Int, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/api/footprint/batch"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `valid batch body returns 200 and forwards query`() {
        val seen = CopyOnWriteArrayList<AggregatesQuery>()

        withBatchServer({ query ->
            seen += query
            emptyList()
        }) { port ->
            val response = post(
                port,
                """
                {
                  "exchange": "Binance",
                  "symbol": "BTCUSDT",
                  "queries": [
                    {"timeframe": "1m", "start": 1000, "end": 2000, "limit": 2, "order": "asc"}
                  ]
                }
                """.trimIndent()
            )

            assertEquals(200, response.statusCode())
            assertEquals(1, seen.size)
            assertEquals("Binance", seen.single().exchange)
            assertEquals("BTCUSDT", seen.single().symbol)
            assertEquals("1m", seen.single().timeframe)
            assertEquals(1000L, seen.single().from)
            assertEquals(2000L, seen.single().to)
            assertEquals(2, seen.single().limit)
            assertEquals("asc", seen.single().order)
        }
    }

    @Test
    fun `unknown fields are ignored and request still succeeds`() {
        val seen = CopyOnWriteArrayList<AggregatesQuery>()

        withBatchServer({ query ->
            seen += query
            emptyList()
        }) { port ->
            val response = post(
                port,
                """
                {
                  "exchange": "Binance",
                  "symbol": "BTCUSDT",
                  "unexpected": true,
                  "queries": [
                    {"timeframe": "1m", "limit": 2, "unexpected": "ignored"}
                  ]
                }
                """.trimIndent()
            )

            assertEquals(200, response.statusCode())
            assertEquals(1, seen.size)
            assertEquals("1m", seen.single().timeframe)
        }
    }

    @Test
    fun `empty queries returns a clear 400`() {
        withBatchServer { port ->
            val response = post(
                port,
                """{"exchange":"Binance","symbol":"BTCUSDT","queries":[]}"""
            )

            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("queries must not be empty"))
        }
    }

    @Test
    fun `malformed body returns 400`() {
        withBatchServer { port ->
            val response = post(port, """{"exchange":"Binance","symbol":""")

            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("invalid request body"))
        }
    }
}
