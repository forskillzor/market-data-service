package com.marketdata

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private val log = KotlinLogging.logger {}
private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}
private const val MAX_LIMIT = 10_000
private const val GZIP_MIN_SIZE = 1024
private val SYMBOL_REGEX = Regex("^[A-Za-z0-9_]+$")

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

@Serializable
data class BatchFootprintQuery(
    val timeframe: String,
    val start: Long? = null,
    val end: Long? = null,
    val limit: Int = 20,
    val order: String = "desc"
)

@Serializable
data class BatchFootprintRequest(
    val exchange: String,
    val symbol: String,
    val queries: List<BatchFootprintQuery>
)

@Serializable
data class BatchFootprintResult(
    val timeframe: String,
    val candles: List<FootprintResponse>
)

@Serializable
data class BatchFootprintResponse(
    val exchange: String,
    val symbol: String,
    val results: List<BatchFootprintResult>
)

internal fun decodeBatchFootprintRequest(body: String): BatchFootprintRequest =
    json.decodeFromString(BatchFootprintRequest.serializer(), body)

class ApiServer(
    private val port: Int,
    private val host: String,
    private val dao: AggregateDAO
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(CIO, port = port, host = host) {
            install(CORS) { anyHost() }

            routing {
                get("/health") {
                    call.respondText(json.encodeToString(mapOf(
                        "status" to if (dao.ping()) "healthy" else "degraded",
                        "service" to "market-data-server"
                    )), ContentType.Application.Json)
                }

                get("/api/aggregates") {
                    call.handle {
                        val exchange = requireParam(call.request.queryParameters["exchange"], "exchange")
                        val symbol = requireSymbol(
                            requireParam(call.request.queryParameters["symbol"], "symbol")
                        )
                        val queryTimeframes = resolveTimeframes(
                            call.request.queryParameters["timeframe"],
                            call.request.queryParameters["timeframes"]
                        )
                        val window = resolveWindow(call)
                        val limit = resolveLimit(call.request.queryParameters["limit"], 1000)
                        val order = resolveOrder(call.request.queryParameters["order"], "asc")

                        val aggregates = queryTimeframes.flatMap { timeframe ->
                            dao.queryAggregates(
                                AggregatesQuery(
                                    exchange = exchange,
                                    symbol = symbol,
                                    timeframe = timeframe,
                                    from = window.start,
                                    to = window.end,
                                    limit = limit,
                                    order = order
                                )
                            ).map { row ->
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
                        }
                        call.respondJson(aggregates)
                    }
                }

                get("/api/instruments") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        call.respondJson(dao.getInstrumentsSummary(exchange))
                    }
                }

                get("/api/symbols") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        call.respondJson(dao.getSymbols(exchange))
                    }
                }

                get("/api/timeframes") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        val symbol = requireSymbol(
                            requireParam(call.request.queryParameters["symbol"], "symbol")
                        )
                        call.respondJson(dao.getTimeframes(exchange, symbol))
                    }
                }

                get("/api/footprint") {
                    call.handle {
                        val exchange = requireParam(call.request.queryParameters["exchange"], "exchange")
                        val symbol = requireSymbol(
                            requireParam(call.request.queryParameters["symbol"], "symbol")
                        )
                        val queryTimeframes = resolveTimeframes(
                            call.request.queryParameters["timeframe"],
                            call.request.queryParameters["timeframes"]
                        )
                        val window = resolveWindow(call)
                        val limit = resolveLimit(call.request.queryParameters["limit"], 20)
                        val order = resolveOrder(call.request.queryParameters["order"], "desc")

                        val footprint = queryTimeframes.flatMap { timeframe ->
                            dao.queryFootprint(
                                AggregatesQuery(
                                    exchange = exchange,
                                    symbol = symbol,
                                    timeframe = timeframe,
                                    from = window.start,
                                    to = window.end,
                                    limit = limit,
                                    order = order
                                )
                            )
                        }
                        call.respondJson(footprint)
                    }
                }

                batchFootprintRoute(dao::queryFootprint)

                get("/api/liquidation-aggregates") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        val symbol = requireSymbol(
                            requireParam(call.request.queryParameters["symbol"], "symbol")
                        )
                        val timeframe = LiquidationTimeframes.normalize(
                            call.request.queryParameters["timeframe"]
                        ) ?: throw RequestException(
                            "unsupported liquidation timeframe; supported: " +
                                LiquidationTimeframes.ordered.joinToString()
                        )
                        val from = parseLong(call.request.queryParameters["from"], "from")
                        val to = parseLong(call.request.queryParameters["to"], "to")
                        if (from != null && to != null && from >= to) {
                            throw RequestException("from must be < to")
                        }
                        val limit = resolveLimit(call.request.queryParameters["limit"], 500)
                        val order = resolveOrder(call.request.queryParameters["order"], "desc")
                        val data = dao.queryLiquidationAggregates(
                            exchange,
                            symbol.uppercase(),
                            timeframe,
                            from,
                            to,
                            limit,
                            order
                        )
                        call.respondJson(data)
                    }
                }

                get("/api/liquidations") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        val symbol = requireSymbol(
                            requireParam(call.request.queryParameters["symbol"], "symbol")
                        )
                        val from = parseLong(call.request.queryParameters["from"], "from")
                        val to = parseLong(call.request.queryParameters["to"], "to")
                        val limit = resolveLimit(call.request.queryParameters["limit"], 100)
                        val data = dao.queryLiquidations(
                            exchange,
                            symbol.uppercase(),
                            from,
                            to,
                            limit
                        )
                        call.respondJson(data)
                    }
                }

                get("/api/footprint/{symbol}/levels") {
                    call.handle {
                        val exchange = call.request.queryParameters["exchange"] ?: "Binance"
                        val symbol = requireSymbol(
                            requireParam(call.parameters["symbol"], "symbol")
                        )
                        val timeframe = (call.request.queryParameters["timeframe"] ?: "1m")
                            .trim()
                            .lowercase()
                        if (!Timeframes.isAllowed(timeframe)) {
                            throw RequestException(
                                "unsupported timeframe '$timeframe'; supported: " +
                                    Timeframes.ordered.joinToString()
                            )
                        }
                        val startTime = parseLong(
                            requireParam(call.request.queryParameters["startTime"], "startTime"),
                            "startTime"
                        ) ?: throw RequestException("startTime is required")
                        val endTime = parseLong(
                            requireParam(call.request.queryParameters["endTime"], "endTime"),
                            "endTime"
                        ) ?: throw RequestException("endTime is required")

                        call.respondJson(
                            dao.queryFootprintLevels(
                                exchange,
                                symbol,
                                timeframe,
                                startTime,
                                endTime
                            )
                        )
                    }
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

internal fun Route.batchFootprintRoute(
    queryFootprint: (AggregatesQuery) -> List<FootprintResponse>
) {
    post("/api/footprint/batch") {
        call.handle {
            val rawBody = try {
                call.receiveText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Failed to read batch footprint request body" }
                throw RequestException("invalid request body")
            }

            val request = try {
                decodeBatchFootprintRequest(rawBody)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) {
                    "Invalid batch footprint request (bodyLength=${rawBody.toByteArray().size} bytes)"
                }
                throw RequestException("invalid request body")
            }

            val exchange = requireParam(request.exchange, "exchange")
            val symbol = requireSymbol(requireParam(request.symbol, "symbol"))
            if (request.queries.isEmpty()) {
                throw RequestException("queries must not be empty")
            }

            var totalLimit = 0
            val results = request.queries.map { query ->
                val timeframe = query.timeframe.trim().lowercase()
                if (!Timeframes.isAllowed(timeframe)) {
                    throw RequestException(
                        "unsupported timeframe '$timeframe'; supported: " +
                            Timeframes.ordered.joinToString()
                    )
                }
                if (query.limit <= 0) {
                    throw RequestException("limit must be positive")
                }
                val limit = query.limit.coerceAtMost(MAX_LIMIT)
                totalLimit += limit
                if (totalLimit > MAX_LIMIT) {
                    throw RequestException("total batch limit exceeds $MAX_LIMIT")
                }
                if (query.start != null && query.end != null && query.start > query.end) {
                    throw RequestException("start must be <= end")
                }

                BatchFootprintResult(
                    timeframe = timeframe,
                    candles = queryFootprint(
                        AggregatesQuery(
                            exchange = exchange,
                            symbol = symbol,
                            timeframe = timeframe,
                            from = query.start,
                            to = query.end,
                            limit = limit,
                            order = resolveOrder(query.order, "desc")
                        )
                    )
                )
            }

            call.respondJson(
                BatchFootprintResponse(
                    exchange = exchange,
                    symbol = symbol,
                    results = results
                )
            )
        }
    }
}

private class RequestException(message: String) : RuntimeException(message)

private data class Window(val start: Long?, val end: Long?)

private fun requireParam(value: String?, name: String): String {
    if (value.isNullOrBlank()) throw RequestException("$name is required")
    return value
}

private fun requireSymbol(symbol: String): String {
    if (!SYMBOL_REGEX.matches(symbol)) throw RequestException("invalid symbol")
    return symbol
}

private fun resolveTimeframes(timeframe: String?, timeframes: String?): List<String> {
    val raw = timeframes?.takeIf { it.isNotBlank() }
        ?: timeframe?.takeIf { it.isNotBlank() }
        ?: throw RequestException("timeframe or timeframes is required")

    if (raw.trim().equals(Timeframes.ALL, ignoreCase = true)) {
        return Timeframes.ordered
    }
    return Timeframes.normalize(listOf(raw))
        ?: throw RequestException(
            "unsupported timeframe; supported: ${Timeframes.ordered.joinToString()}"
        )
}

private fun resolveWindow(call: ApplicationCall): Window {
    val start = parseLong(
        call.request.queryParameters["start"] ?: call.request.queryParameters["from"],
        "start"
    )
    val end = parseLong(
        call.request.queryParameters["end"] ?: call.request.queryParameters["to"],
        "end"
    )
    if (start != null && end != null && start > end) {
        throw RequestException("start must be <= end")
    }
    return Window(start, end)
}

private fun resolveLimit(raw: String?, default: Int): Int {
    if (raw == null) return default
    val value = raw.toIntOrNull() ?: throw RequestException("limit must be an integer")
    return value.coerceIn(1, MAX_LIMIT)
}

private fun resolveOrder(raw: String?, default: String): String {
    val value = (raw ?: default).trim().lowercase()
    if (value != "asc" && value != "desc") {
        throw RequestException("order must be asc or desc")
    }
    return value
}

private fun parseLong(raw: String?, name: String): Long? {
    if (raw == null) return null
    return raw.toLongOrNull() ?: throw RequestException("$name must be an integer")
}

private suspend fun ApplicationCall.handle(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: RequestException) {
        respondError(HttpStatusCode.BadRequest, e.message ?: "bad request")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Request failed" }
        respondError(HttpStatusCode.InternalServerError, "internal error")
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respondText(
        json.encodeToString(mapOf("error" to message)),
        ContentType.Application.Json,
        status
    )
}

private fun ApplicationCall.acceptsGzip(): Boolean {
    val header = request.headers[HttpHeaders.AcceptEncoding] ?: return false
    return header.split(',').any { raw ->
        val token = raw.trim()
        if (!token.startsWith("gzip", ignoreCase = true)) {
            false
        } else {
            token.split(';').drop(1).none { it.trim().startsWith("q=0", ignoreCase = true) }
        }
    }
}

private fun gzip(bytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(bytes.size)
    GZIPOutputStream(output).use { it.write(bytes) }
    return output.toByteArray()
}

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T) {
    val text = json.encodeToString(value)
    val bytes = text.encodeToByteArray()
    if (bytes.size >= GZIP_MIN_SIZE && acceptsGzip()) {
        response.headers.append(HttpHeaders.ContentEncoding, "gzip")
        response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        respondBytes(gzip(bytes), ContentType.Application.Json)
    } else {
        respondText(text, ContentType.Application.Json)
    }
}
