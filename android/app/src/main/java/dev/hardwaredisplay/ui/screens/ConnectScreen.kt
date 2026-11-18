package dev.hardwaredisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import dev.hardwaredisplay.data.HostEntity
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.PairUiState
import dev.hardwaredisplay.ui.Screen
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette

@Composable
fun ConnectScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val pairState by viewModel.pairState.collectAsStateWithLifecycle()
    val activeHostId by viewModel.activeHostId.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Text(
            text = "HardwareDisplay",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
        )
        Text(
            text = "Телефон показывает датчики ПК. На компьютере запустите HardwareDisplay, " +
                "затем отсканируйте QR-код или введите данные приглашения.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
            OutlinedAction("Сканировать QR", { viewModel.navigate(Screen.Scanner) }, accent = true)
            OutlinedAction("Ввести вручную", { viewModel.navigate(Screen.Manual) })
        }

        when (val state = pairState) {
            is PairUiState.Working -> Text(
                text = "Сопряжение…",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Series1,
            )

            is PairUiState.Message -> Text(
                text = state.text,
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Warning,
            )

            PairUiState.Idle -> Unit
        }

        if (hosts.isNotEmpty()) {
            Text(
                text = "Сохранённые ПК",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
            )
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            items(hosts, key = { it.hostId }) { host ->
                HostRow(
                    host = host,
                    isActive = host.hostId == activeHostId,
                    onSelect = { viewModel.selectHost(host.hostId) },
                    onConfirm = { viewModel.confirmPending(host.hostId) },
                    onRename = { viewModel.renameHost(host.hostId, it) },
                    onDelete = { viewModel.removeHost(host.hostId) },
                )
            }
        }
    }
}

@Composable
private fun HostRow(
    host: HostEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onConfirm: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val pending = host.clientId.isEmpty()
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(host.label) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CompactRadius))
            .background(Palette.Card)
            .border(1.dp, if (isActive) Palette.Accent else Palette.Border, RoundedCornerShape(Dimens.CompactRadius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = host.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host.ip + ":" + host.port + if (pending) " · не подтверждён" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pending) Palette.Warning else Palette.TextSecondary,
                )
            }
            if (isActive) {
                Text(
                    text = "Активный",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Accent,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending) {
                OutlinedAction("Подтвердить", onConfirm, accent = true)
            } else {
                OutlinedAction("Подключить", onSelect, accent = !isActive)
            }
            OutlinedAction("Переименовать", { nameDraft = host.label; renaming = true })
            OutlinedAction("Удалить", onDelete)
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Имя ПК") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = nameDraft.trim()
                    if (trimmed.isNotEmpty()) {
                        onRename(trimmed)
                    }
                    renaming = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Отмена") }
            },
            containerColor = Palette.Raised,
        )
    }
}
