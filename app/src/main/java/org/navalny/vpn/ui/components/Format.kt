package org.navalny.vpn.ui.components

import java.util.Locale

private val UNITS = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes ${UNITS[0]}"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
    return String.format(Locale.getDefault(), pattern, value, UNITS[unit])
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/с"

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
}
