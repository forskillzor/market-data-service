package com.marketdata

import com.marketdata.telemetry.AdminServer
import com.marketdata.telemetry.ServiceMetrics
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
    val adminPort = System.getenv("ADMIN_PORT")?.toIntOrNull() ?: 8086
    val adminHost = System.getenv("ADMIN_HOST") ?: "127.0.0.1"

    log.info { "Connecting to PostgreSQL at $dbHost:$dbPort/$dbName" }

    val dataSource = AggregateDAO.createDataSource(dbHost, dbPort, dbName, dbUser, dbPass)
    val dao = AggregateDAO(dataSource)
    val metrics = ServiceMetrics()

    val apiServer = ApiServer(httpPort, httpHost, dao)
    val adminServer = AdminServer(
        port = adminPort,
        host = adminHost,
        metrics = metrics,
        serviceName = "market-data-server",
        version = "1.0.0",
        readiness = { dao.ping() }
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info { "Shutting down..." }
        apiServer.stop()
        adminServer.stop()
        dao.shutdown()
        log.info { "Server stopped" }
    })

    apiServer.start()
    adminServer.start()
    log.info { "Market Data Server ready on $httpHost:$httpPort" }
    log.info { "Admin ready on $adminHost:$adminPort (/health, /ready, /metrics)" }

    Thread.currentThread().join()
}
