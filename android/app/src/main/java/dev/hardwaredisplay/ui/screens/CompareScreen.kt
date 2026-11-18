package dev.hardwaredisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hardwaredisplay.data.CardEntity
import dev.hardwaredisplay.data.CardRole
import dev.hardwaredisplay.data.roleEnum
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.components.SeriesChart
import dev.hardwaredisplay.ui.components.nearestPoint
import dev.hardwaredisplay.ui.components.seriesRange
import dev.hardwaredisplay.ui.format.MetricFormat
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette
import kotlinx.coroutines.delay

private val SERIES_COLORS = listOf(Palette.Accent, Palette.Series1, Palette.Series2)

@Composable
fun CompareScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val windowMs by viewModel.compareWindowMs.collectAsStateWithLifecycle()

    val primary = cards.firstOrNull { it.roleEnum == CardRole.PRIMARY }
    val compareCards = cards.filter { it.roleEnum == CardRole.COMPARE }
    val rows: List<CardEntity> = (listOfNotNull(primary) + compareCards).take(3)

    var points by remember { mutableStateOf<Map<String, List<HistoryPointEntity>>>(emptyMap()) }
    var cursor by remember { mutableStateOf<Float?>(null) }
    var windowStart by remember { mutableStateOf(System.currentTimeMillis() - windowMs) }

    LaunchedEffect(rows.map { it.sensorId }, windowMs) {
        while (true) {
            val now = System.currentTimeMillis()
            val loaded = rows.associate { card ->
                card.sensorId to viewModel.historyFor(card.sensorId, windowMs)
            }
            points = loaded
            val recordedFrom = loaded.values.flatten().minOfOrNull { it.capturedAtUtc }
            windowStart = maxOf(now - windowMs, (recordedFrom ?: now) - 1_000L)
            delay(3_000)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5L, 15L, 60L).forEach { minutes ->
                val millis = minutes * 60_000L
                val selected = millis == windowMs
                Box(
                    Modifier
                        .height(Dimens.MinTouch)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Palette.Raised else Palette.Card)
                        .border(1.dp, if (selected) Palette.Accent else Palette.Border, RoundedCornerShape(50))
                        .clickable { viewModel.setCompareWindow(millis); cursor = null }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = minutes.toString() + " мин",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Palette.Accent else Palette.TextSecondary,
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = "Нет датчиков для сравнения. Добавьте карточки в редакторе.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
            return@Column
        }

        val visibleSpan = maxOf(10_000L, System.currentTimeMillis() - windowStart)
        val cursorTime = cursor?.let { windowStart + (it * visibleSpan).toLong() }

        rows.forEachIndexed { index, card ->
            val sensor = catalog?.sensors?.get(card.sensorId)
            val series = points[card.sensorId].orEmpty()
            val range = seriesRange(series, windowStart, visibleSpan)
            val color = SERIES_COLORS[index % SERIES_COLORS.size]

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.CompactRadius))
                    .background(Palette.Card)
                    .border(1.dp, Palette.Border, RoundedCornerShape(Dimens.CompactRadius))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                        .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(color)
                    )
                    Text(
                        text = sensor?.label ?: "Датчик недоступен",
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Text,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    Text(
                        text = sensor?.let { MetricFormat.unitText(it.unit, range?.second ?: 0.0) }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextSecondary,
                    )
                }

                Text(
                    text = "мин " + (range?.first?.let { MetricFormat.withUnit(sensor?.unit ?: "", it) } ?: "—") +
                        "   макс " + (range?.second?.let { MetricFormat.withUnit(sensor?.unit ?: "", it) } ?: "—"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary,
                )

                SeriesChart(
                    points = series,
                    color = color,
                    windowStartUtc = windowStart,
                    windowMs = visibleSpan,
                    cursorFraction = cursor,
                    onCursor = { cursor = it },
                )

                val point = cursorTime?.let { nearestPoint(series, it) }
                Text(
                    text = when {
                        series.isEmpty() -> "Запись началась"
                        cursor == null -> "Коснитесь графика, чтобы увидеть значение"
                        point == null -> "Нет замера в этой точке"
                        else -> MetricFormat.clockTime(point.capturedAtUtc) + " · " +
                            MetricFormat.withUnit(sensor?.unit ?: "", point.value)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (point == null) Palette.TextSecondary else Palette.Text,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
            OutlinedAction("Раньше", {
                val current = cursor ?: 1f
                cursor = (current - 0.02f).coerceIn(0f, 1f)
            })
            OutlinedAction("Позже", {
                val current = cursor ?: 0f
                cursor = (current + 0.02f).coerceIn(0f, 1f)
            })
            OutlinedAction("Сброс курсора", { cursor = null })
        }

        Text(
            text = "Курсор общий для всех рядов: касание или перетаскивание по графику показывает время и значения.",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
        )
    }
}
