package com.marketdata

object LiquidationTimeframes {
    private const val WEEK_MS = 604_800_000L
    private const val MONDAY_OFFSET_MS = 259_200_000L

    val ordered: List<String> = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")
    private val allowed: Set<String> = ordered.toSet()
    private val durations: Map<String, Long> = mapOf(
        "1m" to 60_000L,
        "5m" to 300_000L,
        "15m" to 900_000L,
        "30m" to 1_800_000L,
        "1h" to 3_600_000L,
        "4h" to 14_400_000L,
        "1d" to 86_400_000L,
        "1w" to WEEK_MS,
    )

    fun durationMs(timeframe: String): Long? = durations[timeframe.lowercase()]

    fun normalize(raw: String?): String? {
        val code = (raw ?: "1m").trim().lowercase()
        return code.takeIf { it in allowed }
    }

    fun bucketStart(timestampMs: Long, timeframe: String): Long {
        val duration = durationMs(timeframe) ?: return timestampMs
        return if (timeframe.lowercase() == "1w") {
            (timestampMs + MONDAY_OFFSET_MS) / duration * duration - MONDAY_OFFSET_MS
        } else {
            timestampMs / duration * duration
        }
    }
}
