package com.marketdata

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import mu.KotlinLogging

private val log = KotlinLogging.logger {}
private val json = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
data class InstrumentSummary(
    val symbol: String,
    val start: Long,
    val end: Long,
    val candles: Long
)

@Serializable
data class AggregateResponse(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Long,
    val endTime: Long,
    val totalTicks: Long,
    val minPrice: String,
    val maxPrice: String,
    val priceLevels: Int,
    val priceLevelsJson: String? = null
)

@Serializable
data class FootprintLevel(
    val price: String,
    val bidVolume: String,
    val askVolume: String,
    val bidCount: Int,
    val askCount: Int
)

@Serializable
data class FootprintResponse(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Long,
    val endTime: Long,
    val totalTicks: Long,
    val minPrice: String,
    val maxPrice: String,
    val levels: List<FootprintLevel>
)

@Serializable
data class LiquidationData(
    val exchange: String,
    val symbol: String,
    val timestamp: Long,
    val price: String,
    val quantity: String,
    val isLong: Boolean,
    val orderType: String
)

@Serializable
data class LiquidationAggregateData(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Long,
    val endTime: Long,
    val longCount: Int,
    val longVolume: String,
    val shortCount: Int,
    val shortVolume: String
)

class ApiServer(
    private val port: Int,
    private val host: String,
    private val dao: AggregateDAO
) {
    private var server: EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration>? = null

    fun start() {
        server = embeddedServer(Jetty, port = port, host = host) {
            install(CORS) { anyHost() }

            routing {
                get("/health") {
                    call.respondText(json.encodeToString(mapOf(
                        "status" to if (dao.ping()) "healthy" else "degraded",
                        "service" to "market-data-server"
                    )), ContentType.Application.Json)
                }

                get("/api/aggregates") {
                    val exchange = call.request.queryParameters["exchange"] ?: ""
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    val timeframe = call.request.queryParameters["timeframe"] ?: ""
                    val from = call.request.queryParameters["from"]?.toLongOrNull()
                    val to = call.request.queryParameters["to"]?.toLongOrNull()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000

                    if (exchange.isBlank() || symbol.isBlank() || timeframe.isBlank()) {
                        call.respondText(json.encodeToString(mapOf(
                            "error" to "exchange, symbol, timeframe are required"
                        )), ContentType.Application.Json)
                        return@get
                    }

                    val query = AggregatesQuery(exchange, symbol, timeframe, from, to, limit)
                    val aggregates = dao.queryAggregates(query).map { row ->
                        AggregateResponse(
                            exchange = row["exchange"] as String,
                            symbol = row["symbol"] as String,
                            timeframe = row["timeframe"] as String,
                            startTime = row["startTime"] as Long,
                            endTime = row["endTime"] as Long,
                            totalTicks = row["totalTicks"] as Long,
                            minPrice = row["minPrice"]?.toString() ?: "0",
                            maxPrice = row["maxPrice"]?.toString() ?: "0",
                            priceLevels = row["priceLevels"] as Int,
                            priceLevelsJson = row["priceLevelsJson"] as? String
                        )
                    }
                    call.respondText(json.encodeToString(aggregates), ContentType.Application.Json)
                }

                get("/api/instruments") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val summary = dao.getInstrumentsSummary(exchange)
                    call.respondText(json.encodeToString(summary), ContentType.Application.Json)
                }

                get("/api/symbols") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbols = dao.getSymbols(exchange)
                    call.respondText(json.encodeToString(symbols), ContentType.Application.Json)
                }

                get("/api/timeframes") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    if (symbol.isBlank()) {
                        call.respondText(json.encodeToString(mapOf("error" to "symbol required")), ContentType.Application.Json)
                        return@get
                    }
                    val tfs = dao.getTimeframes(exchange, symbol)
                    call.respondText(json.encodeToString(tfs), ContentType.Application.Json)
                }

                get("/api/footprint") {
                    val exchange = call.request.queryParameters["exchange"] ?: ""
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    val timeframe = call.request.queryParameters["timeframe"] ?: ""
                    val from = call.request.queryParameters["from"]?.toLongOrNull()
                    val to = call.request.queryParameters["to"]?.toLongOrNull()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                    if (exchange.isBlank() || symbol.isBlank() || timeframe.isBlank()) {
                        call.respondText(json.encodeToString(mapOf(
                            "error" to "exchange, symbol, timeframe are required"
                        )), ContentType.Application.Json)
                        return@get
                    }

                    val query = AggregatesQuery(exchange, symbol, timeframe, from, to, limit)
                    val footprint = dao.queryFootprint(query)
                    call.respondText(json.encodeToString(footprint), ContentType.Application.Json)
                }

                get("/api/liquidation-aggregates") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    val timeframe = call.request.queryParameters["timeframe"] ?: "1m"
                    val from = call.request.queryParameters["from"]?.toLongOrNull()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                    if (symbol.isBlank()) {
                        call.respondText(json.encodeToString(mapOf("error" to "symbol required")), ContentType.Application.Json)
                        return@get
                    }
                    val data = dao.queryLiquidationAggregates(exchange, symbol.uppercase(), timeframe, from, limit)
                    call.respondText(json.encodeToString(data), ContentType.Application.Json)
                }

                get("/api/liquidations") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    val from = call.request.queryParameters["from"]?.toLongOrNull()
                    val to = call.request.queryParameters["to"]?.toLongOrNull()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                    if (symbol.isBlank()) {
                        call.respondText(json.encodeToString(mapOf("error" to "symbol required")), ContentType.Application.Json)
                        return@get
                    }
                    val data = dao.queryLiquidations(exchange, symbol.uppercase(), from, to, limit)
                    call.respondText(json.encodeToString(data), ContentType.Application.Json)
                }

                get("/api/footprint/{symbol}/levels") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbol = call.parameters["symbol"] ?: ""
                    val timeframe = call.request.queryParameters["timeframe"] ?: "1m"
                    val startTime = call.request.queryParameters["startTime"]?.toLongOrNull()
                    val endTime = call.request.queryParameters["endTime"]?.toLongOrNull()

                    if (symbol.isBlank() || startTime == null || endTime == null) {
                        call.respondText(json.encodeToString(mapOf(
                            "error" to "symbol, startTime, endTime are required"
                        )), ContentType.Application.Json)
                        return@get
                    }

                    val levels = dao.queryFootprintLevels(exchange, symbol, timeframe, startTime, endTime)
                    call.respondText(json.encodeToString(levels), ContentType.Application.Json)
                }
            }
        }
        server!!.start(wait = false)
        log.info { "API server started on $host:$port" }
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
    }
}
