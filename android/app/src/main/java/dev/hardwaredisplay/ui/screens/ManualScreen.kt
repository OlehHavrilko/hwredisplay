package dev.hardwaredisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hardwaredisplay.protocol.ManualEntryFields
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.PairUiState
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette

@Composable
fun ManualScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val pairState by viewModel.pairState.collectAsStateWithLifecycle()

    var raw by remember { mutableStateOf("") }
    var pcName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("18443") }
    var fingerprint by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
    ) {
        Text(
            text = "Ввод приглашения",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
        )
        Text(
            text = "Скопируйте данные из окна HardwareDisplay на ПК целиком или перенесите поля по одному.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
        )

        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it },
            label = { Text("Данные приглашения (JSON)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        OutlinedAction("Подключить по данным", { viewModel.manualJson(raw) }, accent = true)

        Text(
            text = "Поля с экрана Windows",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
        )
        OutlinedTextField(
            value = pcName,
            onValueChange = { pcName = it },
            label = { Text("Имя ПК (pc-…hardwaredisplay.test)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Адрес") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Порт") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Код приглашения") },
                modifier = Modifier.weight(2f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it },
            label = { Text("Отпечаток сертификата") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedAction(
            text = "Подключить",
            onClick = {
                viewModel.manualEntry(
                    ManualEntryFields(
                        pcName = pcName,
                        address = address,
                        port = port,
                        fingerprint = fingerprint,
                        code = code,
                    )
                )
            },
            accent = true,
        )

        val state = pairState
        if (state is PairUiState.Message) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Warning,
            )
        }
        if (state is PairUiState.Working) {
            Text(
                text = "Сопряжение…",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Series1,
            )
        }
    }
}
