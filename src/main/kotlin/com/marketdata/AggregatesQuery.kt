package com.marketdata

data class AggregatesQuery(
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val from: Long? = null,
    val to: Long? = null,
    val limit: Int = 1000
)
