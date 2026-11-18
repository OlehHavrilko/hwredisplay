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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hardwaredisplay.data.CardRole
import dev.hardwaredisplay.data.CardView
import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.CardDraft
import dev.hardwaredisplay.ui.PresetDraft
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.format.MetricFormat
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette

@Composable
fun EditorScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    val current = draft
    if (current == null) {
        Column(
            modifier
                .fillMaxSize()
                .background(Palette.Background)
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            Text(
                text = "Пресет не открыт",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
            )
            OutlinedAction("Изменить текущий", {
                viewModel.startEdit(presets.firstOrNull { it.presetId == activePresetId })
            }, accent = true)
            OutlinedAction("Создать новый", { viewModel.startEdit(null) })
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Text(
            text = if (current.presetId == null) "Новый пресет" else "Редактор пресета",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
        )

        OutlinedTextField(
            value = current.name,
            onValueChange = viewModel::draftRename,
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        CardPreview(draft = current, sensors = catalog?.sensors?.values?.associateBy { it.sensorId }.orEmpty())

        current.cards.forEachIndexed { index, card ->
            EditorCardRow(
                card = card,
                position = index,
                sensorLabel = catalog?.sensors?.get(card.sensorId)?.label ?: "Датчик недоступен",
                unit = catalog?.sensors?.get(card.sensorId)?.unit.orEmpty(),
                onMoveUp = { viewModel.draftMove(card.cardId, -1) },
                onMoveDown = { viewModel.draftMove(card.cardId, 1) },
                onCycleView = { viewModel.draftCycleView(card.cardId) },
                onPrimary = { viewModel.draftSetPrimary(card.cardId) },
                onToggleCompare = { viewModel.draftToggleCompare(card.cardId) },
                onReplace = { pickingFor = card.cardId },
                onRemove = { viewModel.draftRemove(card.cardId) },
            )
        }

        OutlinedAction("Добавить датчик", { adding = true })

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
            OutlinedAction("Сохранить", { viewModel.saveDraft() }, accent = true)
            OutlinedAction("Сохранить копию", { viewModel.saveDraft(copy = true) })
            OutlinedAction("Отмена", {
                viewModel.discardDraft()
                viewModel.back()
            })
        }

        if (current.presetId != null) {
            OutlinedAction("Удалить пресет", {
                presets.firstOrNull { it.presetId == current.presetId }?.let {
                    viewModel.deletePreset(it)
                    viewModel.discardDraft()
                    viewModel.back()
                }
            })
        }
    }

    if (pickingFor != null || adding) {
        SensorPicker(
            sensors = viewModel.sensorsForSelection(),
            deviceLabels = catalog?.devices?.mapValues { it.value.label }.orEmpty(),
            onDismiss = { pickingFor = null; adding = false },
            onPick = { sensor ->
                if (adding) {
                    viewModel.draftAddSensor(sensor)
                } else {
                    pickingFor?.let { viewModel.draftReplaceSensor(it, sensor) }
                }
                pickingFor = null
                adding = false
            },
        )
    }
}

@Composable
private fun CardPreview(draft: PresetDraft, sensors: Map<String, SensorDto>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CompactRadius))
            .background(Palette.Card)
            .border(1.dp, Palette.Border, RoundedCornerShape(Dimens.CompactRadius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Раскладка",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
        )
        Text(
            text = draft.primary?.let { sensors[it.sensorId]?.label ?: it.sensorId } ?: "Главный датчик не выбран",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = draft.secondary.joinToString(", ") { sensors[it.sensorId]?.label ?: it.sensorId }
                .ifEmpty { "Дополнительных карточек нет" },
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Сравнение: " + draft.compare.joinToString(", ") { sensors[it.sensorId]?.label ?: it.sensorId }
                .ifEmpty { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Series1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorCardRow(
    card: CardDraft,
    position: Int,
    sensorLabel: String,
    unit: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCycleView: () -> Unit,
    onPrimary: () -> Unit,
    onToggleCompare: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    val roleLabel = when (card.role) {
        CardRole.PRIMARY -> "Главная"
        CardRole.SECONDARY -> "Дополнительная"
        CardRole.COMPARE -> "Сравнение"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CompactRadius))
            .background(Palette.Card)
            .border(1.dp, Palette.Border, RoundedCornerShape(Dimens.CompactRadius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = sensorLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = roleLabel + " · " + viewLabel(card.view) + " · " + MetricFormat.unitName(unit),
                    style = MaterialTheme.typography.labelMedium,
                    color = roleColor(card.role),
                )
            }
            Text(
                text = (position + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Выше", onMoveUp)
            OutlinedAction("Ниже", onMoveDown)
            OutlinedAction(if (card.view == CardView.NUMBER) "График" else "Число", onCycleView)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Главная", onPrimary)
            OutlinedAction(if (card.role == CardRole.COMPARE) "Не сравнивать" else "К сравнению", onToggleCompare)
            OutlinedAction("Заменить", onReplace, accent = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Убрать", onRemove)
        }
    }
}

private fun viewLabel(view: CardView): String =
    if (view == CardView.NUMBER) "число" else "график"

private fun roleColor(role: CardRole) = when (role) {
    CardRole.PRIMARY -> Palette.Accent
    CardRole.SECONDARY -> Palette.TextSecondary
    CardRole.COMPARE -> Palette.Series1
}

@Composable
private fun SensorPicker(
    sensors: List<SensorDto>,
    deviceLabels: Map<String, String>,
    onDismiss: () -> Unit,
    onPick: (SensorDto) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите датчик") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sensors.forEach { sensor ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.Card)
                            .clickable { onPick(sensor) }
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = sensor.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.Text,
                            )
                            Text(
                                text = (deviceLabels[sensor.deviceId] ?: sensor.deviceId) + " · " + sensor.metricKey,
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.TextSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        containerColor = Palette.Raised,
    )
}
