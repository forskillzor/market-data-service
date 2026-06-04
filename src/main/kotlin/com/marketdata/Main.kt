package com.marketdata

import mu.KotlinLogging

private val log = KotlinLogging.logger {}

fun main() {
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
    val dbName = System.getenv("DB_NAME") ?: "trade_collector"
    val dbUser = System.getenv("DB_USER") ?: "trade_user"
    val dbPass = System.getenv("DB_PASSWORD") ?: "dev_password"
    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8085
    val httpHost = System.getenv("HTTP_HOST") ?: "0.0.0.0"

    log.info { "Connecting to PostgreSQL at $dbHost:$dbPort/$dbName" }

    val dataSource = AggregateDAO.createDataSource(dbHost, dbPort, dbName, dbUser, dbPass)
    val dao = AggregateDAO(dataSource)

    val server = ApiServer(httpPort, httpHost, dao)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info { "Shutting down..." }
        server.stop()
        dao.shutdown()
        log.info { "Server stopped" }
    })

    server.start()
    log.info { "Market Data Server ready on $httpHost:$httpPort" }
    log.info { "Endpoints: /health, /api/aggregates, /api/symbols, /api/timeframes" }

    Thread.currentThread().join()
}
