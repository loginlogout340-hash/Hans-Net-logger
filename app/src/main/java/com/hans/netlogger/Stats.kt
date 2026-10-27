package com.hans.netlogger

import java.util.Calendar
import java.util.Locale

data class Row(val t: Long, val spot: String, val dbm: Int, val type: String, val mbps: Double)

object Stats {
    fun parse(lines: List<String>): List<Row> = lines.mapNotNull { l ->
        val p = l.split(",")
        try { Row(p[0].toLong(), p[1], p[2].toInt(), p[3], p[4].toDouble()) } catch (e: Exception) { null }
    }

    private fun f(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun hourLine(h: Pair<Int, Double>) = String.format(Locale.US, "%02d:00  %s Mbps\n", h.first, f(h.second))

    fun report(rows: List<Row>): String {
        if (rows.isEmpty()) return "No data yet. Tap Start logging."
        val ok = rows.filter { it.mbps > 0 }
        val avg = if (ok.isEmpty()) 0.0 else ok.map { it.mbps }.average()
        val hours = rows.groupBy { Calendar.getInstance().apply { timeInMillis = it.t }.get(Calendar.HOUR_OF_DAY) }
            .mapValues { e -> e.value.map { it.mbps }.average() }.toList().sortedByDescending { it.second }
        val spots = rows.groupBy { it.spot }
            .map { (k, v) -> Triple(k, v.map { it.mbps }.average(), v.map { it.dbm }.average()) }
            .sortedByDescending { it.second }
        val sb = StringBuilder("Samples: ${rows.size}   Uptime: ${100 * ok.size / rows.size}%   Avg: ${f(avg)} Mbps\n\nBest hours:\n")
        hours.take(3).forEach { sb.append(hourLine(it)) }
        if (hours.size > 3) {
            sb.append("\nWeakest hours:\n")
            hours.takeLast(3).reversed().forEach { sb.append(hourLine(it)) }
        }
        sb.append("\nSpots (best first):\n")
        spots.forEach { sb.append("${it.first}: ${f(it.second)} Mbps · ${it.third.toInt()} dBm\n") }
        return sb.toString()
    }
}
