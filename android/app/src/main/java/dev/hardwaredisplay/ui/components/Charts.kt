package dev.hardwaredisplay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.ui.theme.Palette
import kotlin.math.max
import kotlin.math.min

private const val GAP_MS = 6_000L

@Composable
fun MiniSparkline(
    points: List<HistoryPointEntity>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val visible = points.takeLast(120)
        if (visible.size < 2) return@Canvas

        val minValue = visible.minOf { it.value }
        val maxValue = visible.maxOf { it.value }
        val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val startTime = visible.first().capturedAtUtc
        val timeSpan = (visible.last().capturedAtUtc - startTime).takeIf { it > 0 } ?: 1L
        val stroke = Stroke(width = 2f * density)

        var path: Path? = null
        var previousTime = 0L
        visible.forEach { point ->
            val x = ((point.capturedAtUtc - startTime).toFloat() / timeSpan) * size.width
            val y = size.height - (((point.value - minValue) / span) * size.height).toFloat()
            if (path == null || point.capturedAtUtc - previousTime > GAP_MS) {
                path?.let { drawPath(it, color, style = stroke) }
                path = Path().apply { moveTo(x, y) }
            } else {
                path?.lineTo(x, y)
            }
            previousTime = point.capturedAtUtc
        }
        path?.let { drawPath(it, color, style = stroke) }
    }
}

@Composable
fun SeriesChart(
    points: List<HistoryPointEntity>,
    color: Color,
    windowStartUtc: Long,
    windowMs: Long,
    cursorFraction: Float?,
    onCursor: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(96.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .pointerInput(windowMs) {
                    detectTapGestures { offset ->
                        onCursor((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(windowMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onCursor((offset.x / size.width).coerceIn(0f, 1f)) },
                        onHorizontalDrag = { change, _ ->
                            onCursor((change.position.x / size.width).coerceIn(0f, 1f))
                        },
                    )
                }
        ) {
            val tickColor = Palette.Border
            for (index in 0..4) {
                val x = size.width * index / 4f
                drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            if (points.size >= 2) {
                val minValue = points.minOf { it.value }
                val maxValue = points.maxOf { it.value }
                val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                val stroke = Stroke(width = 2f * density)
                val right = windowStartUtc + windowMs

                var path: Path? = null
                var previousTime = 0L
                for (point in points) {
                    if (point.capturedAtUtc < windowStartUtc || point.capturedAtUtc > right) {
                        path?.let { drawPath(it, color, style = stroke) }
                        path = null
                        continue
                    }
                    val x = ((point.capturedAtUtc - windowStartUtc).toFloat() / windowMs) * size.width
                    val y = size.height - (((point.value - minValue) / span) * (size.height * 0.86)).toFloat() -
                        size.height * 0.07f
                    if (path == null || point.capturedAtUtc - previousTime > GAP_MS) {
                        path?.let { drawPath(it, color, style = stroke) }
                        path = Path().apply { moveTo(x, y) }
                    } else {
                        path?.lineTo(x, y)
                    }
                    previousTime = point.capturedAtUtc
                }
                path?.let { drawPath(it, color, style = stroke) }
            }

            cursorFraction?.let { fraction ->
                val x = size.width * fraction.coerceIn(0f, 1f)
                drawLine(Palette.TextSecondary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f * density)
            }
        }

        if (points.size < 2) {
            Text(
                text = if (points.isEmpty()) "Запись началась" else "Нужно больше точек",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

fun nearestPoint(points: List<HistoryPointEntity>, targetUtc: Long): HistoryPointEntity? {
    if (points.isEmpty()) return null
    var best: HistoryPointEntity? = null
    var bestDistance = Long.MAX_VALUE
    for (point in points) {
        val distance = kotlin.math.abs(point.capturedAtUtc - targetUtc)
        if (distance < bestDistance) {
            bestDistance = distance
            best = point
        }
    }
    return best?.takeIf { bestDistance <= GAP_MS * 2 }
}

fun seriesRange(points: List<HistoryPointEntity>, windowStartUtc: Long, windowMs: Long): Pair<Double, Double>? {
    val visible = points.filter { it.capturedAtUtc in windowStartUtc..(windowStartUtc + windowMs) }
    if (visible.isEmpty()) return null
    return visible.minOf { it.value } to visible.maxOf { it.value }
}

fun chartScaleLabel(points: List<HistoryPointEntity>, windowStartUtc: Long, windowMs: Long): String {
    val range = seriesRange(points, windowStartUtc, windowMs) ?: return "—"
    val low = min(range.first, range.second)
    val high = max(range.first, range.second)
    return String.format(java.util.Locale.forLanguageTag("ru-RU"), "%.1f…%.1f", low, high)
}
