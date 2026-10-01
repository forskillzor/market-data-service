package com.marketdata

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class AggregateDAO(private val dataSource: HikariDataSource) {

    companion object {
        fun createDataSource(host: String, port: Int, db: String, user: String, pass: String): HikariDataSource {
            val cfg = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://$host:$port/$db"
                username = user
                password = pass
                maximumPoolSize = 10
                minimumIdle = 2
                connectionTimeout = 10_000
                idleTimeout = 300_000
                poolName = "MarketDataPool"
                connectionTestQuery = "SELECT 1"
            }
            return HikariDataSource(cfg)
        }
    }

    private fun tableName(symbol: String) = "aggregates_${symbol.lowercase()}"

    private val symbolListCache = ConcurrentHashMap<String, List<String>>()
    private var symbolListCacheTime = 0L
    private val symbolCacheTtl = 30_000L

    private fun getAllSymbols(): List<String> {
        val now = System.currentTimeMillis()
        if (now - symbolListCacheTime < symbolCacheTtl && symbolListCache.isNotEmpty()) {
            return symbolListCache.values.flatten().distinct().sorted()
        }
        val symbols = dataSource.connection.use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public' AND tablename LIKE 'aggregates\\_%'"
            )
            val result = mutableListOf<String>()
            while (rs.next()) {
                val name = rs.getString("tablename")
                val symbol = name.removePrefix("aggregates_").uppercase()
                result.add(symbol)
            }
            result.sorted()
        }
        symbolListCacheTime = now
        symbols.forEach { symbolListCache[it] = listOf(it) }
        return symbols
    }

    fun queryAggregates(q: AggregatesQuery): List<Map<String, Any?>> {
        val tbl = tableName(q.symbol)
        val sql = buildString {
            append("""
                SELECT exchange, symbol, timeframe, start_time, end_time,
                       total_ticks, min_price, max_price, price_levels,
                       price_levels_jsonb
                FROM $tbl
                WHERE exchange = ? AND symbol = ? AND timeframe = ?
            """.trimIndent())
            if (q.from != null) append(" AND start_time >= ?")
            if (q.to != null) append(" AND end_time <= ?")
            append(" ORDER BY start_time ASC LIMIT ?")
        }

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, q.exchange)
                stmt.setString(idx++, q.symbol)
                stmt.setString(idx++, q.timeframe)
                if (q.from != null) stmt.setLong(idx++, q.from)
                if (q.to != null) stmt.setLong(idx++, q.to)
                stmt.setInt(idx, q.limit)

                val rs = stmt.executeQuery()
                val results = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    results.add(mapOf(
                        "exchange" to rs.getString("exchange"),
                        "symbol" to rs.getString("symbol"),
                        "timeframe" to rs.getString("timeframe"),
                        "startTime" to rs.getLong("start_time"),
                        "endTime" to rs.getLong("end_time"),
                        "totalTicks" to rs.getLong("total_ticks"),
                        "minPrice" to rs.getBigDecimal("min_price"),
                        "maxPrice" to rs.getBigDecimal("max_price"),
                        "priceLevels" to rs.getInt("price_levels"),
                        "priceLevelsJson" to rs.getString("price_levels_jsonb")
                    ))
                }
                results
            }
        }
    }

    fun getSymbols(exchange: String): List<String> {
        val allSym = getAllSymbols()
        if (allSym.isEmpty()) return emptyList()

        return dataSource.connection.use { conn ->
            val queries = allSym.joinToString(" UNION ALL ") { sym ->
                "(SELECT DISTINCT symbol FROM ${tableName(sym)} WHERE exchange = '${exchange.replace("'", "''")}')"
            }
            if (queries.isEmpty()) return emptyList()
            val rs = conn.createStatement().executeQuery(queries)
            val syms = mutableListOf<String>()
            while (rs.next()) syms.add(rs.getString("symbol"))
            syms.sorted()
        }
    }

    fun getTimeframes(exchange: String, symbol: String): List<String> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT DISTINCT timeframe FROM ${tableName(symbol)}
                WHERE exchange = ? AND symbol = ? ORDER BY timeframe
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)
                val rs = stmt.executeQuery()
                val tfs = mutableListOf<String>()
                while (rs.next()) tfs.add(rs.getString("timeframe"))
                tfs
            }
        }
    }

    fun getInstrumentsSummary(exchange: String): List<InstrumentSummary> {
        val allSym = getAllSymbols()
        if (allSym.isEmpty()) return emptyList()

        val results = mutableListOf<InstrumentSummary>()
        dataSource.connection.use { conn ->
            allSym.forEach { sym ->
                try {
                    conn.prepareStatement("""
                        SELECT symbol, MIN(start_time) as data_start, MAX(end_time) as data_end, COUNT(*) as candle_count
                        FROM ${tableName(sym)}
                        WHERE exchange = ?
                        GROUP BY symbol ORDER BY symbol
                    """).use { stmt ->
                        stmt.setString(1, exchange)
                        val rs = stmt.executeQuery()
                        while (rs.next()) {
                            results.add(InstrumentSummary(
                                symbol = rs.getString("symbol"),
                                start = rs.getLong("data_start"),
                                end = rs.getLong("data_end"),
                                candles = rs.getLong("candle_count")
                            ))
                        }
                    }
                } catch (_: Exception) {
                    // table might not exist yet
                }
            }
        }
        return results
    }

    fun queryFootprint(q: AggregatesQuery): List<FootprintResponse> {
        val tbl = tableName(q.symbol)
        val sql = buildString {
            append("""
                SELECT exchange, symbol, timeframe, start_time, end_time,
                       total_ticks, min_price, max_price, price_levels,
                       price_levels_jsonb::text as price_levels_json
                FROM $tbl
                WHERE exchange = ? AND symbol = ? AND timeframe = ?
            """.trimIndent())
            if (q.from != null) append(" AND start_time >= ?")
            if (q.to != null) append(" AND end_time <= ?")
            append(" ORDER BY start_time DESC LIMIT ?")
        }

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, q.exchange)
                stmt.setString(idx++, q.symbol)
                stmt.setString(idx++, q.timeframe)
                if (q.from != null) stmt.setLong(idx++, q.from)
                if (q.to != null) stmt.setLong(idx++, q.to)
                stmt.setInt(idx, q.limit)

                val rs = stmt.executeQuery()
                val results = mutableListOf<FootprintResponse>()
                while (rs.next()) {
                    val levels = parsePriceLevels(rs.getString("price_levels_json"))

                    results.add(FootprintResponse(
                        exchange = rs.getString("exchange"),
                        symbol = rs.getString("symbol"),
                        timeframe = rs.getString("timeframe"),
                        startTime = rs.getLong("start_time"),
                        endTime = rs.getLong("end_time"),
                        totalTicks = rs.getLong("total_ticks"),
                        minPrice = rs.getBigDecimal("min_price")?.toPlainString() ?: "0",
                        maxPrice = rs.getBigDecimal("max_price")?.toPlainString() ?: "0",
                        levels = levels
                    ))
                }
                results
            }
        }
    }

    fun queryFootprintLevels(
        exchange: String, symbol: String, timeframe: String,
        startTime: Long, endTime: Long
    ): List<FootprintLevel> {
        val tbl = tableName(symbol)
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT price_levels_jsonb::text as price_levels_json
                FROM $tbl
                WHERE exchange = ? AND symbol = ? AND timeframe = ?
                  AND start_time = ? AND end_time = ?
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)
                stmt.setString(3, timeframe)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                val rs = stmt.executeQuery()
                if (rs.next()) parsePriceLevels(rs.getString("price_levels_json"))
                else emptyList()
            }
        }
    }

    fun queryLiquidationAggregates(
        exchange: String, symbol: String, timeframe: String = "1m",
        from: Long? = null, limit: Int = 100
    ): List<LiquidationAggregateData> {
        val tbl = "liquidation_aggregates_${symbol.lowercase()}"
        val sql = buildString {
            append("SELECT * FROM $tbl WHERE 1=1")
            if (from != null) append(" AND start_time >= ?")
            append(" ORDER BY start_time DESC LIMIT ?")
        }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                if (from != null) stmt.setLong(idx++, from)
                stmt.setInt(idx, limit)
                val rs = stmt.executeQuery()
                val results = mutableListOf<LiquidationAggregateData>()
                while (rs.next()) {
                    results.add(LiquidationAggregateData(
                        exchange = exchange, symbol = symbol, timeframe = timeframe,
                        startTime = rs.getLong("start_time"),
                        endTime = rs.getLong("end_time"),
                        longCount = rs.getInt("long_count"),
                        longVolume = rs.getBigDecimal("long_volume")?.toPlainString() ?: "0",
                        shortCount = rs.getInt("short_count"),
                        shortVolume = rs.getBigDecimal("short_volume")?.toPlainString() ?: "0"
                    ))
                }
                results
            }
        }
    }

    fun queryLiquidations(
        exchange: String, symbol: String,
        from: Long? = null, to: Long? = null, limit: Int = 100
    ): List<LiquidationData> {
        val tbl = "liquidations_${symbol.lowercase()}"
        val sql = buildString {
            append("SELECT * FROM $tbl WHERE 1=1")
            if (from != null) append(" AND timestamp >= ?")
            if (to != null) append(" AND timestamp <= ?")
            append(" ORDER BY timestamp DESC LIMIT ?")
        }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                if (from != null) stmt.setLong(idx++, from)
                if (to != null) stmt.setLong(idx++, to)
                stmt.setInt(idx, limit)
                val rs = stmt.executeQuery()
                val results = mutableListOf<LiquidationData>()
                while (rs.next()) {
                    results.add(LiquidationData(
                        exchange = exchange, symbol = symbol,
                        timestamp = rs.getLong("timestamp"),
                        price = rs.getBigDecimal("price")?.toPlainString() ?: "0",
                        quantity = rs.getBigDecimal("quantity")?.toPlainString() ?: "0",
                        isLong = rs.getBoolean("is_long"),
                        orderType = rs.getString("order_type") ?: ""
                    ))
                }
                results
            }
        }
    }

    private fun parsePriceLevels(jsonText: String?): List<FootprintLevel> {
        if (jsonText.isNullOrBlank()) return emptyList()
        return try {
            val element = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(jsonText)
            val arr = element.jsonArray
            arr.map { levelElement ->
                val levelArr = levelElement.jsonArray
                FootprintLevel(
                    price = levelArr[0].jsonPrimitive.content,
                    bidVolume = levelArr.getOrNull(1)?.jsonPrimitive?.content ?: "0",
                    askVolume = levelArr.getOrNull(2)?.jsonPrimitive?.content ?: "0",
                    bidCount = levelArr.getOrNull(3)?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    askCount = levelArr.getOrNull(4)?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to parse price_levels_json" }
            emptyList()
        }
    }

    fun ping(): Boolean = try {
        dataSource.connection.use { it.prepareStatement("SELECT 1").use { s -> s.executeQuery().use { it.next() } } }
        true
    } catch (e: Exception) { false }

    fun shutdown() { dataSource.close() }
}
