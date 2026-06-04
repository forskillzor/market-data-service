package com.marketdata

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val log = KotlinLogging.logger {}
private val jmapper = jacksonObjectMapper()

class ApiServer(
    private val port: Int,
    private val host: String,
    private val dao: AggregateDAO
) {
    private var server: JettyApplicationEngine? = null

    fun start() {
        server = embeddedServer(Jetty, port = port, host = host) {
            install(CORS) { anyHost() }

            routing {
                get("/health") {
                    call.respondText(jmapper.writeValueAsString(mapOf(
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
                        call.respondText(jmapper.writeValueAsString(mapOf(
                            "error" to "exchange, symbol, timeframe are required"
                        )), ContentType.Application.Json)
                        return@get
                    }

                    val query = AggregatesQuery(exchange, symbol, timeframe, from, to, limit)
                    val aggregates = dao.queryAggregates(query)
                    val json = jmapper.writeValueAsString(aggregates)
                    call.respondText(json, ContentType.Application.Json)
                }

                get("/api/symbols") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbols = dao.getSymbols(exchange)
                    call.respondText(jmapper.writeValueAsString(symbols), ContentType.Application.Json)
                }

                get("/api/timeframes") {
                    val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                    val symbol = call.request.queryParameters["symbol"] ?: ""
                    if (symbol.isBlank()) {
                        call.respondText(jmapper.writeValueAsString(mapOf("error" to "symbol required")), ContentType.Application.Json)
                        return@get
                    }
                    val tfs = dao.getTimeframes(exchange, symbol)
                    call.respondText(jmapper.writeValueAsString(tfs), ContentType.Application.Json)
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
