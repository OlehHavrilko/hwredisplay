package dev.hardwaredisplay.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hardwaredisplay.data.CardEntity
import dev.hardwaredisplay.data.CardRole
import dev.hardwaredisplay.data.roleEnum
import dev.hardwaredisplay.data.CardView
import dev.hardwaredisplay.data.viewEnum
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.session.ConnectionState
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.MetricTone
import dev.hardwaredisplay.ui.MetricUi
import dev.hardwaredisplay.ui.Screen
import dev.hardwaredisplay.ui.buildMetricUi
import dev.hardwaredisplay.ui.components.CompactMetricCard
import dev.hardwaredisplay.ui.components.MainMetricCard
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.components.StatusPill
import dev.hardwaredisplay.ui.connectionLabel
import dev.hardwaredisplay.ui.format.MetricFormat
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun PanelScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val host by viewModel.activeHost.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    val activePreset = presets.firstOrNull { it.presetId == activePresetId } ?: presets.firstOrNull()
    val primaryCard = cards.firstOrNull { it.roleEnum == CardRole.PRIMARY } ?: cards.firstOrNull()
    val secondaryCards = cards.filter { it.roleEnum == CardRole.SECONDARY }
    val lineCards = secondaryCards.filter { it.viewEnum == CardView.LINE }

    var history by remember { mutableStateOf<Map<String, List<HistoryPointEntity>>>(emptyMap()) }
    LaunchedEffect(activePresetId, lineCards.map { it.sensorId }) {
        while (true) {
            if (lineCards.isEmpty()) {
                history = emptyMap()
            } else {
                history = lineCards.associate { card ->
                    card.sensorId to viewModel.historyFor(card.sensorId, 15 * 60_000L)
                }
            }
            delay(5_000)
        }
    }

    fun metric(card: CardEntity?): MetricUi {
        if (card == null) {
            return MetricUi("Нет карточек", null, null, null, "Откройте редактор и добавьте датчик", MetricTone.MISSING)
        }
        val sensor = catalog?.sensors?.get(card.sensorId)
        val deviceLabel = sensor?.let { catalog?.devices?.get(it.deviceId)?.label }
        return buildMetricUi(
            sensor = sensor,
            reading = telemetry?.readings?.get(card.sensorId),
            deviceLabel = deviceLabel,
            connection = connection,
            nowElapsedMs = now,
            receivedAtElapsedMs = telemetry?.receivedAtUptimeMs,
            lastSampleUtcMs = telemetry?.capturedAtUtcMs,
        )
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Palette.Background)) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            HeaderRow(
                hostLabel = host?.label ?: "ПК не выбран",
                connection = connection,
                keepScreenOn = keepScreenOn,
                onOpenHosts = { viewModel.navigate(Screen.Connect) },
                onToggleKeepScreen = viewModel::toggleKeepScreenOn,
            )

            if (connection is ConnectionState.Lost || connection is ConnectionState.Failed) {
                LinkLostRow(
                    text = connectionLabel(connection) + (telemetry?.let {
                        ", последний замер " + MetricFormat.clockTime(it.capturedAtUtcMs)
                    } ?: ""),
                    onRetry = viewModel::reconnect,
                )
            }

            if (connection is ConnectionState.AccessDenied ||
                connection is ConnectionState.TrustFailed ||
                connection is ConnectionState.Incompatible
            ) {
                LinkLostRow(
                    text = when (connection) {
                        is ConnectionState.AccessDenied -> "Доступ отозван на ПК. Нужно новое приглашение."
                        is ConnectionState.TrustFailed -> "Не удалось подтвердить ПК: сертификат не совпал."
                        else -> "Версия протокола несовместима с приложением."
                    },
                    onRetry = { viewModel.navigate(Screen.Connect) },
                    actionLabel = "Сопрячь заново",
                )
            }

            PresetRow(
                presets = presets.map { it.presetId to it.name },
                activePresetId = activePreset?.presetId,
                onSelect = viewModel::selectPreset,
            )

            val primaryUi = metric(primaryCard)

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
                    MainMetricCard(
                        ui = primaryUi,
                        onOpen = { viewModel.navigate(Screen.Compare) },
                        modifier = Modifier.weight(1.1f),
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                    ) {
                        SecondaryGrid(
                            cards = secondaryCards,
                            metric = ::metric,
                            history = history,
                            columns = 1,
                            onOpen = { viewModel.navigate(Screen.Compare) },
                        )
                    }
                }
            } else {
                MainMetricCard(
                    ui = primaryUi,
                    onOpen = { viewModel.navigate(Screen.Compare) },
                )
                SecondaryGrid(
                    cards = secondaryCards,
                    metric = ::metric,
                    history = history,
                    columns = 2,
                    onOpen = { viewModel.navigate(Screen.Compare) },
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
                OutlinedAction("Сравнить", { viewModel.navigate(Screen.Compare) }, accent = true)
                OutlinedAction("Изменить", {
                    viewModel.startEdit(activePreset)
                    viewModel.navigate(Screen.Editor)
                })
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HeaderRow(
    hostLabel: String,
    connection: ConnectionState,
    keepScreenOn: Boolean,
    onOpenHosts: () -> Unit,
    onToggleKeepScreen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenHosts)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = hostLabel,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Сменить ПК",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
            )
        }

        StatusPill(
            text = connectionLabel(connection),
            tone = when (connection) {
                is ConnectionState.Live -> MetricTone.FRESH
                is ConnectionState.Connecting -> MetricTone.WAITING
                is ConnectionState.Lost -> MetricTone.STALE
                is ConnectionState.NoHost, is ConnectionState.Stopped -> MetricTone.OFFLINE
                else -> MetricTone.MISSING
            },
        )

        Box(
            Modifier
                .height(Dimens.MinTouch)
                .clip(RoundedCornerShape(50))
                .background(if (keepScreenOn) Palette.Accent else Palette.Raised)
                .clickable(onClick = onToggleKeepScreen)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (keepScreenOn) "Экран вкл" else "Экран выкл",
                style = MaterialTheme.typography.labelMedium,
                color = if (keepScreenOn) Palette.Background else Palette.TextSecondary,
            )
        }
    }
}

@Composable
private fun LinkLostRow(text: String, onRetry: () -> Unit, actionLabel: String = "Повторить") {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CompactRadius))
            .background(Palette.Raised)
            .border(1.dp, Palette.Warning, RoundedCornerShape(Dimens.CompactRadius))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Warning,
            modifier = Modifier.weight(1f),
        )
        OutlinedAction(actionLabel, onRetry)
    }
}

@Composable
private fun PresetRow(
    presets: List<Pair<String, String>>,
    activePresetId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { (id, name) ->
            val active = id == activePresetId
            Box(
                Modifier
                    .height(Dimens.MinTouch)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Palette.Raised else Palette.Card)
                    .border(1.dp, if (active) Palette.Accent else Palette.Border, RoundedCornerShape(50))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Palette.Accent else Palette.TextSecondary,
                )
            }
        }

    }
}

@Composable
private fun SecondaryGrid(
    cards: List<CardEntity>,
    metric: (CardEntity?) -> MetricUi,
    history: Map<String, List<HistoryPointEntity>>,
    columns: Int,
    onOpen: () -> Unit,
) {
    if (cards.isEmpty()) {
        Text(
            text = "Дополнительных карточек нет. Добавьте их в редакторе.",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
        cards.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
            ) {
                row.forEach { card ->
                    CompactMetricCard(
                        ui = metric(card),
                        onOpen = onOpen,
                        modifier = Modifier.weight(1f),
                        points = if (card.viewEnum == CardView.LINE) history[card.sensorId] else null,
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
