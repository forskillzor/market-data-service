package com.marketdata

object Timeframes {
    const val ALL = "all"

    val ordered: List<String> = listOf("1m", "5m", "15m", "1h", "4h", "1d")
    private val allowed: Set<String> = ordered.toSet()

    fun isAllowed(code: String): Boolean = code.lowercase() in allowed

    fun normalize(codes: Collection<String>): List<String>? {
        val normalized = codes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty() || normalized.any { it !in allowed }) return null
        return normalized.distinct()
    }

    fun sortAvailable(codes: Collection<String>): List<String> {
        return codes.distinct().sortedWith(
            compareBy<String> { code ->
                val index = ordered.indexOf(code.lowercase())
                if (index < 0) Int.MAX_VALUE else index
            }.thenBy { it }
        )
    }
}
