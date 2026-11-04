package be.lain.bloom.feature.food

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.data.food.BarcodeResult
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.ui.Route
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

sealed interface ScanState {
    data object Scanning : ScanState
    data class Looking(val code: String) : ScanState
    data class NotFound(val code: String) : ScanState
    data class Offline(val code: String, val message: String) : ScanState
}

@HiltViewModel(assistedFactory = ScanViewModel.Factory::class)
class ScanViewModel @AssistedInject constructor(
    @Assisted val key: Route.Scan,
    private val repo: FoodRepository,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.Scan): ScanViewModel }

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state = _state.asStateFlow()
    private val _found = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val found = _found.asSharedFlow()

    fun onCode(code: String) {
        if (_state.value !is ScanState.Scanning) return
        _state.value = ScanState.Looking(code)
        viewModelScope.launch {
            when (val r = repo.lookupBarcode(code)) {
                is BarcodeResult.Found -> _found.tryEmit(r.food.id)
                is BarcodeResult.NotFound -> _state.value = ScanState.NotFound(code)
                is BarcodeResult.Offline -> _state.value = ScanState.Offline(code, r.message)
            }
        }
    }

    fun rescan() { _state.value = ScanState.Scanning }
}

@Composable
fun ScanScreen(
    key: Route.Scan,
    onBack: () -> Unit,
    replace: (Route) -> Unit,
    vm: ScanViewModel = hiltViewModel<ScanViewModel, ScanViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by vm.state.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var manual by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) {
        vm.found.collect { id ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            replace(Route.LogFood(id, key.date, key.meal))
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize().graphicsLayer { }) {
        if (granted) {
            CameraPreview(onCamera = { camera = it }, onCode = { code ->
                if (state is ScanState.Scanning) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    vm.onCode(code)
                }
            })
            ScanOverlay()
        } else {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ShapeIcon(MaterialShapes.Cookie9Sided, Icons.Rounded.QrCodeScanner, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text("Camera access is needed to scan barcodes.", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = { manual = true }) { Text("Type the barcode instead") }
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (granted) {
                FilledTonalIconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn, if (torch) "Torch off" else "Torch on")
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (val s = state) {
                        is ScanState.Looking -> {
                            LoadingIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.size(12.dp))
                            Text("Looking up ${s.code}…")
                        }
                        else -> Text("Point at a barcode")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { manual = true }) {
                Icon(Icons.Rounded.Keyboard, null)
                Spacer(Modifier.size(8.dp))
                Text("Type barcode")
            }
        }
    }

    if (manual) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Enter barcode") },
            text = {
                OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(14) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Digits under the barcode") })
            },
            confirmButton = { TextButton(onClick = { manual = false; vm.onCode(code) }, enabled = code.length >= 8) { Text("Look up") } },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
        )
    }

    val missing = state as? ScanState.NotFound
    val offline = state as? ScanState.Offline
    if (missing != null || offline != null) {
        val code = missing?.code ?: offline!!.code
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = vm::rescan, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(if (missing != null) "Not found yet" else "Can't look it up right now", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (missing != null) "Barcode $code isn't in Open Food Facts. Add it once and it scans instantly next time."
                    else "You seem to be offline (${offline!!.message}). You can still add it yourself.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { replace(Route.FoodEditor(barcode = code, date = key.date, meal = key.meal, aiMode = "barcode")) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Let AI find it", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { replace(Route.FoodEditor(barcode = code, date = key.date, meal = key.meal)) },
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Rounded.EditNote, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Enter it myself", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                TextButton(onClick = vm::rescan, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Scan again") }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onCamera: (Camera) -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }
    var last by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val code = codes.firstNotNullOfOrNull { b -> b.rawValue?.filter { it.isDigit() }?.takeIf { it.length in 8..14 } }
                        // Require the same code on two frames in a row to avoid misreads.
                        if (code != null && code == last) onCode(code)
                        last = code
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                onCamera(provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
            scanner.close()
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}

@Composable
private fun ScanOverlay() {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width * 0.8f
        val h = w * 0.55f
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2.3f)
        val radius = CornerRadius(32.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, topLeft, Size(w, h), radius, blendMode = BlendMode.Clear)
        drawRoundRect(accent, topLeft, Size(w, h), radius, style = Stroke(4.dp.toPx()))
    }
}
