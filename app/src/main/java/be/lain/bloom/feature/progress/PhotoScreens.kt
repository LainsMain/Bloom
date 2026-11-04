package be.lain.bloom.feature.progress

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.core.util.signed
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ai.CoachEngine
import be.lain.bloom.data.db.ProgressPhotoEntity
import be.lain.bloom.data.review.ReviewRepository
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import be.lain.bloom.feature.coach.rememberThumbnail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class PhotoData(val photos: List<ProgressPhotoEntity>, val trend: (LocalDate) -> Double?, val measure: (LocalDate) -> Pair<Double?, Double?>)

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val reviews: ReviewRepository,
    body: BodyRepository,
    private val engine: CoachEngine,
    private val messages: AppMessages,
) : ViewModel() {
    val data = combine(reviews.photos, body.weights, body.measurements) { photos, weights, measures ->
        val pts = weights.map { WeightPoint(it.date, it.kg) }
        PhotoData(
            photos.sortedBy { it.date },
            trend = { d -> if (pts.none { !it.date.isAfter(d) }) null else Trend.at(pts, d) },
            // The measurement taken closest to the photo (within 3 weeks).
            measure = { d ->
                measures.filter { abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, d)) <= 21 }
                    .minByOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, d)) }
                    ?.let { it.waistCm to it.hipsCm } ?: (null to null)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stores a captured file upright and downscaled as a progress photo. */
    fun save(file: File, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { engine.importImage(Uri.fromFile(file), folder = "progress_photos") }
            .onSuccess { reviews.addPhoto(it); messages.show("Progress photo saved") }
            .onFailure { messages.show("Couldn't save the photo") }
        file.delete()
        onDone()
    }
}

// ---------- Compare ----------

@Composable
fun PhotoCompareScreen(onBack: () -> Unit, vm: PhotosViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare photos") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val d = data ?: return@Scaffold
        if (d.photos.size < 2) {
            Text("Add at least two progress photos to compare.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        var beforeId by rememberSaveable { mutableStateOf(d.photos.first().id) }
        var afterId by rememberSaveable { mutableStateOf(d.photos.last().id) }
        var choosing by rememberSaveable { mutableStateOf("after") }
        var slider by rememberSaveable { mutableStateOf(false) }
        val before = d.photos.firstOrNull { it.id == beforeId } ?: d.photos.first()
        val after = d.photos.firstOrNull { it.id == afterId } ?: d.photos.last()

        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectedChoice(listOf(false, true), slider, { if (it) "Slider" else "Side by side" }, { slider = it })
            if (slider) BeforeAfterSlider(before.path, after.path)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledPhoto(before, "Before", Modifier.weight(1f))
                LabeledPhoto(after, "After", Modifier.weight(1f))
            }
            if (slider) Row(Modifier.fillMaxWidth()) {
                Text("Before · ${before.date.shortWithYear()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("After · ${after.date.shortWithYear()}", style = MaterialTheme.typography.labelLarge)
            }
            ChangeSummary(d, before.date, after.date)

            Text("Pick photos", style = MaterialTheme.typography.titleMedium)
            ConnectedChoice(listOf("before", "after"), choosing, { if (it == "before") "Choosing before" else "Choosing after" }, { choosing = it })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.photos, key = { it.id }) { p ->
                    val selected = (choosing == "before" && p.id == before.id) || (choosing == "after" && p.id == after.id)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val img = rememberThumbnail(p.path, 256)
                        Box(
                            Modifier.width(72.dp).aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                                .padding(if (selected) 3.dp else 0.dp)
                                .clickable { if (choosing == "before") beforeId = p.id else afterId = p.id },
                        ) {
                            if (img != null) Image(img, "Photo of ${p.date}", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small))
                        }
                        Text(p.date.shortWithYear(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledPhoto(p: ProgressPhotoEntity, label: String, modifier: Modifier) {
    Column(modifier) {
        val img = rememberThumbnail(p.path, 1280)
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (img != null) Image(img, "$label photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text("$label · ${p.date.shortWithYear()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "After" over "before", revealed by dragging the divider. */
@Composable
private fun BeforeAfterSlider(beforePath: String, afterPath: String) {
    val before = rememberThumbnail(beforePath, 1280)
    val after = rememberThumbnail(afterPath, 1280)
    var split by remember { mutableFloatStateOf(0.5f) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        if (before != null) Image(before, "Before photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (after != null) Image(
            after, "After photo", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().drawWithContent {
                clipRect(left = size.width * split) { this@drawWithContent.drawContent() }
            },
        )
        val x = maxWidth * split
        Box(Modifier.offset(x = x - 1.5.dp).width(3.dp).fillMaxHeight().background(Color.White))
        Surface(shape = CircleShape, color = Color.White, shadowElevation = 4.dp,
            modifier = Modifier.offset(x = x - 18.dp).size(36.dp).align(Alignment.CenterStart)) {
            Box(contentAlignment = Alignment.Center) { Text("⇆", color = Color.Black) }
        }
    }
}

@Composable
private fun ChangeSummary(d: PhotoData, from: LocalDate, to: LocalDate) {
    val w0 = d.trend(from); val w1 = d.trend(to)
    val (waist0, hips0) = d.measure(from); val (waist1, hips1) = d.measure(to)
    val days = java.time.temporal.ChronoUnit.DAYS.between(from, to)
    val parts = buildList {
        add("${abs(days)} days apart")
        if (w0 != null && w1 != null) add("trend weight ${(w1 - w0).signed()} kg")
        if (waist0 != null && waist1 != null && waist0 != waist1) add("waist ${(waist1 - waist0).signed()} cm")
        if (hips0 != null && hips1 != null && hips0 != hips1) add("hips ${(hips1 - hips0).signed()} cm")
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp))
    }
}

// ---------- Guided camera ----------

/** Camera with a faint "ghost" of the last photo to line up the same pose, and a self-timer. */
@Composable
fun ProgressCameraScreen(onBack: () -> Unit, vm: PhotosViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var front by rememberSaveable { mutableStateOf(true) }
    var ghostOn by rememberSaveable { mutableStateOf(true) }
    var ghostAlpha by rememberSaveable { mutableFloatStateOf(0.35f) }
    var timer by rememberSaveable { mutableIntStateOf(3) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Preview and photo share one 3:4 view port, so the saved photo is exactly the framed preview and the
    // ghost of the last photo lines up with it (3:4 is also how photos are shown side by side).
    val fourThree = remember {
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setResolutionSelector(fourThree).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    if (granted) DisposableEffect(lifecycleOwner, front) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setResolutionSelector(fourThree).build().also { it.surfaceProvider = previewView.surfaceProvider }
            val group = androidx.camera.core.UseCaseGroup.Builder()
                .setViewPort(androidx.camera.core.ViewPort.Builder(android.util.Rational(3, 4), android.view.Surface.ROTATION_0).build())
                .addUseCase(preview).addUseCase(capture).build()
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, group)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    fun shoot() {
        if (busy) return
        busy = true
        scope.launch {
            for (s in timer downTo 1) { countdown = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); delay(1000) }
            countdown = 0
            val file = File(context.cacheDir, "progress-${System.currentTimeMillis()}.jpg")
            capture.takePicture(
                // CameraX mirrors front-camera shots by default; save every photo unmirrored so front, back and
                // gallery photos all compare the same way round (the ghost is mirrored to match the preview).
                ImageCapture.OutputFileOptions.Builder(file).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = false }).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.save(file, onDone = onBack)
                    }
                    override fun onError(exception: ImageCaptureException) { busy = false }
                },
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp).fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
                val last = data?.photos?.lastOrNull()
                if (ghostOn && last != null) {
                    val ghost = rememberThumbnail(last.path, 1280)
                    // Photos are saved unmirrored but the front preview is a mirror, so flip the ghost to match it.
                    if (ghost != null) Image(ghost, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = ghostAlpha; scaleX = if (front) -1f else 1f })
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera access is needed to take progress photos.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Close") }
            Spacer(Modifier.weight(1f))
            if (data?.photos?.isNotEmpty() == true) {
                FilledTonalIconButton(onClick = { ghostOn = !ghostOn }) {
                    Icon(if (ghostOn) Icons.Rounded.Layers else Icons.Rounded.LayersClear, if (ghostOn) "Hide last photo" else "Show last photo")
                }
            }
            FilledTonalIconButton(onClick = { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }) { Icon(Icons.Rounded.Timer, "Timer: ${timer}s") }
            FilledTonalIconButton(onClick = { front = !front }) { Icon(Icons.Rounded.Cameraswitch, "Switch camera") }
        }
        if (countdown > 0) Text("$countdown", style = MaterialTheme.typography.displayLarge, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = MaterialTheme.shapes.large, color = Color.Black.copy(alpha = 0.55f)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        if (ghostOn && data?.photos?.isNotEmpty() == true) "Line yourself up with the outline of your last photo. Same spot, same light."
                        else "Same spot, same light and the same pose each time makes changes easy to see.",
                        color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                    if (ghostOn && data?.photos?.isNotEmpty() == true) {
                        Slider(value = ghostAlpha, onValueChange = { ghostAlpha = it }, valueRange = 0.1f..0.7f, modifier = Modifier.width(260.dp))
                    }
                    Text(if (timer == 0) "Timer off" else "${timer}-second timer", color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledIconButton(onClick = ::shoot, enabled = granted && !busy, modifier = Modifier.size(76.dp), shape = CircleShape) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}
