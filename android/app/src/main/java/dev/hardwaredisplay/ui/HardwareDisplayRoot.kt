package dev.hardwaredisplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.screens.CompareScreen
import dev.hardwaredisplay.ui.screens.ConnectScreen
import dev.hardwaredisplay.ui.screens.EditorScreen
import dev.hardwaredisplay.ui.screens.ManualScreen
import dev.hardwaredisplay.ui.screens.PanelScreen
import dev.hardwaredisplay.ui.screens.ScannerScreen
import dev.hardwaredisplay.ui.theme.Palette

@Composable
fun HardwareDisplayRoot(viewModel: AppViewModel) {
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val current = stack.last()

    BackHandler(enabled = stack.size > 1) {
        viewModel.back()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        if (stack.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedAction("Назад", { viewModel.back() })
                Text(
                    text = when (current) {
                        Screen.Scanner -> "Сканирование"
                        Screen.Manual -> "Ручной ввод"
                        Screen.Connect -> "ПК и подключение"
                        Screen.Compare -> "Сравнение"
                        Screen.Editor -> "Редактор"
                        Screen.Panel -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        when (current) {
            Screen.Connect -> ConnectScreen(viewModel)
            Screen.Scanner -> ScannerScreen(viewModel)
            Screen.Manual -> ManualScreen(viewModel)
            Screen.Panel -> PanelScreen(viewModel)
            Screen.Compare -> CompareScreen(viewModel)
            Screen.Editor -> EditorScreen(viewModel)
        }
    }
}
