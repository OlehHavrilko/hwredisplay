package dev.hardwaredisplay.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.hardwaredisplay.protocol.InviteParse
import dev.hardwaredisplay.protocol.parseInvite
import dev.hardwaredisplay.ui.AppViewModel
import dev.hardwaredisplay.ui.PairUiState
import dev.hardwaredisplay.ui.Screen
import dev.hardwaredisplay.ui.components.OutlinedAction
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun ScannerScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pairState by viewModel.pairState.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier.fillMaxSize().background(Palette.Background)) {
        if (!granted) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "Нет доступа к камере",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                )
                Text(
                    text = "Разрешите камеру, чтобы сканировать QR. Данные приглашения можно ввести вручную.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
                OutlinedAction("Разрешить камеру", {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }, accent = true)
                OutlinedAction("Ввести вручную", { viewModel.navigate(Screen.Manual) })
            }
        } else {
            CameraScanner(
                onPayload = { payload ->
                    when (val parsed = parseInvite(payload)) {
                        is InviteParse.Valid -> viewModel.inviteCommand(parsed.invite)
                        is InviteParse.Invalid -> viewModel.manualJson(payload)
                    }
                },
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Dimens.ScreenPadding),
            ) {
                val state = pairState
                Text(
                    text = when (state) {
                        is PairUiState.Working -> "Сопряжение…"
                        is PairUiState.Message -> state.text
                        PairUiState.Idle -> "Наведите камеру на QR-код в окне ПК"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when (state) {
                        is PairUiState.Message -> Palette.Warning
                        else -> Palette.TextSecondary
                    },
                )
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanner(onPayload: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(Unit) {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy: ImageProxy ->
            val mediaImage = proxy.image
            if (mediaImage == null || handled) {
                proxy.close()
                return@setAnalyzer
            }

            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue
                    if (!handled && value != null) {
                        handled = true
                        onPayload(value)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }
}
