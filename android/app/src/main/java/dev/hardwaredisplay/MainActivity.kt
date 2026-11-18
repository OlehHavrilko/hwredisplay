package dev.hardwaredisplay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.HardwareDisplayRoot
import dev.hardwaredisplay.ui.Screen
import dev.hardwaredisplay.ui.theme.HardwareDisplayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HardwareDisplayTheme {
                val viewModel: AppViewModel = viewModel()
                val stack by viewModel.stack.collectAsStateWithLifecycle()
                val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()

                LaunchedEffect(stack, keepScreenOn) {
                    val onPanel = stack.lastOrNull() == Screen.Panel
                    if (keepScreenOn && onPanel) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                HardwareDisplayRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as HardwareDisplayApp).repository.resume()
    }

    override fun onStop() {
        (application as HardwareDisplayApp).repository.pause()
        super.onStop()
    }
}
