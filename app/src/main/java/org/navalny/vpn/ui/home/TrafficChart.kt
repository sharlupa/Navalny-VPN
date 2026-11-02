package org.navalny.vpn.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.navalny.vpn.ui.TrafficUi

/**
 * Speed chart for the last minute: download (filled area) and upload (line).
 * Pure Canvas, redrawn only when a new sample arrives (once per second while visible).
 */
@Composable
fun TrafficChart(
    down: List<Long>,
    up: List<Long>,
    modifier: Modifier = Modifier,
) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    val rawMax = maxOf(down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L, 16 * 1024L).toFloat()
    val maxValue by animateFloatAsState(rawMax * 1.15f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "scale")

    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        // grid: 3 horizontal lines
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val downPath = buildPath(down, maxValue)
        if (downPath != null) {
            val fill = Path().apply {
                addPath(downPath)
                lineTo(size.width, size.height)
                lineTo(xFor(TrafficUi.HISTORY - down.size), size.height)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(listOf(downColor.copy(alpha = 0.35f), Color.Transparent)),
            )
            drawPath(downPath, downColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        buildPath(up, maxValue)?.let {
            drawPath(it, upColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun DrawScope.xFor(index: Int): Float = size.width * index / (TrafficUi.HISTORY - 1).toFloat()

/** Smooth (monotone-ish cubic) path, right-aligned so the newest sample is at the right edge. */
private fun DrawScope.buildPath(values: List<Long>, max: Float): Path? {
    if (values.size < 2) return null
    val offset = TrafficUi.HISTORY - values.size
    val points = values.mapIndexed { i, v ->
        Offset(xFor(offset + i), size.height - (v / max).coerceIn(0f, 1f) * size.height)
    }
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p0 = points[i - 1]
            val p1 = points[i]
            val midX = (p0.x + p1.x) / 2
            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
        }
    }
}
