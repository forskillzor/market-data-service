package com.marketdata

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging

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

    fun queryAggregates(q: AggregatesQuery): List<Map<String, Any?>> {
        val sql = buildString {
            append("""
                SELECT exchange, symbol, timeframe, start_time, end_time,
                       total_ticks, min_price, max_price, price_levels,
                       price_levels_jsonb
                FROM aggregates
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
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT DISTINCT symbol FROM aggregates WHERE exchange = ? ORDER BY symbol")
                .use { stmt ->
                    stmt.setString(1, exchange)
                    val rs = stmt.executeQuery()
                    val syms = mutableListOf<String>()
                    while (rs.next()) syms.add(rs.getString("symbol"))
                    syms
                }
        }
    }

    fun getTimeframes(exchange: String, symbol: String): List<String> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT DISTINCT timeframe FROM aggregates WHERE exchange = ? AND symbol = ? ORDER BY timeframe")
                .use { stmt ->
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
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT symbol,
                       MIN(start_time) as data_start,
                       MAX(end_time) as data_end,
                       COUNT(*) as candle_count
                FROM aggregates
                WHERE exchange = ?
                GROUP BY symbol
                ORDER BY symbol
            """.trimIndent()).use { stmt ->
                stmt.setString(1, exchange)
                val rs = stmt.executeQuery()
                val results = mutableListOf<InstrumentSummary>()
                while (rs.next()) {
                    results.add(InstrumentSummary(
                        symbol = rs.getString("symbol"),
                        start = rs.getLong("data_start"),
                        end = rs.getLong("data_end"),
                        candles = rs.getLong("candle_count")
                    ))
                }
                results
            }
        }
    }

    fun ping(): Boolean = try {
        dataSource.connection.use { it.prepareStatement("SELECT 1").use { s -> s.executeQuery().use { it.next() } } }
        true
    } catch (e: Exception) { false }

    fun shutdown() { dataSource.close() }
}
