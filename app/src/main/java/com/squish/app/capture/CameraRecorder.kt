package com.squish.app.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.squish.app.editor.LabeledSlider
import com.squish.app.editor.rememberPermission
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.theme.SquishColors
import java.io.File

/**
 * The in-app camera (EDGE_PLAN G5): front or back, a 3-2-1 count-in, a 9:16
 * guide, and a teleprompter that scrolls the script at a reading pace while
 * the take runs. A take is written under files/imports, so it is the new
 * project's from the start, as the phone's own camera app's was; that app is
 * still one tap away for a phone this one cannot drive.
 */
@Composable
fun CameraRecorder(
    newFile: () -> File,
    onTaken: (File) -> Unit,
    onPhoneCamera: () -> Unit,
    onClose: () -> Unit
) {
    // The system bars as the activity's window has them. A dialog drawn under the
    // bars gets no insets of its own (safeDrawingPadding read zero), and one fitted
    // to them still drew under the three-button bar on the moto - the record row sat
    // behind Home - so the room is measured on the window that does know.
    // (Read through the view's root insets they came back as nothing at the bottom
    // while a take rolled; Compose's own insets on the activity are what the rest
    // of the app pads by, and they are right.)
    val bars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val camera = rememberPermission(Manifest.permission.CAMERA)
        val mic = rememberPermission(Manifest.permission.RECORD_AUDIO)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (!camera.granted) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                ) {
                    Text("Squish needs the camera to record here.", color = SquishColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    SquishPrimaryButton(
                        if (camera.deniedForGood) "Open Settings" else "Allow camera",
                        modifier = Modifier.fillMaxWidth()
                    ) { if (camera.deniedForGood) camera.openSettings() else camera.ask() }
                    SquishOutlinedButton("Use the phone's camera app", modifier = Modifier.fillMaxWidth(), onClick = onPhoneCamera)
                    SquishOutlinedButton("Cancel", modifier = Modifier.fillMaxWidth(), onClick = onClose)
                }
            } else {
                // The mic is asked once on the way in; a take without it is silent and says so.
                LaunchedEffect(Unit) { if (!mic.granted && !mic.deniedForGood) mic.ask() }
                Recorder(mic.granted, bars, newFile, onTaken, onPhoneCamera, onClose)
            }
        }
    }
}

private sealed interface Phase {
    object Idle : Phase
    class Counting(val since: Long) : Phase
    class Rolling(val since: Long) : Phase
}

@SuppressLint("MissingPermission") // The mic is only enabled under mic.granted.
@Composable
private fun Recorder(
    withSound: Boolean,
    bars: androidx.compose.foundation.layout.PaddingValues,
    newFile: () -> File,
    onTaken: (File) -> Unit,
    onPhoneCamera: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(true) }
    var countIn by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(true) }
    var prompter by remember { mutableStateOf(false) }
    var editingScript by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf(Script.load(context)) }
    var wpm by remember { mutableStateOf(Script.pace(context)) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var now by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableStateOf<String?>(null) }
    // TextureView, not the default SurfaceView: a SurfaceView draws behind its window,
    // and this screen is a dialog with a black background over it - the camera ran
    // and the preview stayed black on the moto.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val capture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        // A selfie take the right way round, as every phone's camera app writes it.
        VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var file by remember { mutableStateOf<File?>(null) }

    // Bound to this screen's lifecycle, again for each lens.
    LaunchedEffect(front) {
        val provider = cameraProvider(context) ?: run { failed = "This phone's camera would not open."; return@LaunchedEffect }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val lens = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, lens, preview, capture)
        }.onFailure { failed = if (front) "The front camera would not open." else "The back camera would not open." }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    // The clock, while counting or rolling.
    LaunchedEffect(phase) {
        if (phase is Phase.Idle) return@LaunchedEffect
        while (true) {
            withFrameMillis { now = System.currentTimeMillis() }
            val p = phase
            if (p is Phase.Counting && CaptureRules.countIn(now - p.since) == null) {
                phase = Phase.Rolling(System.currentTimeMillis())
                start(context, capture, withSound, newFile()) { r, f -> recording = r; file = f }
                    ?.let { failed = it; phase = Phase.Idle }
            }
        }
    }
    fun stop() {
        val r = recording ?: run { phase = Phase.Idle; return }
        recording = null
        phase = Phase.Idle
        r.stop()
    }
    // The take is in once the recorder says the file is finished, not when Stop is pressed.
    LaunchedEffect(Unit) {
        finished.collect { (f, error) ->
            if (error == null && f.length() > 0L) onTaken(f)
            else { f.delete(); failed = error ?: "The take was empty." }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera preview" })
        if (guide) GuideFrame()
        if (prompter && script.isNotBlank()) {
            val rolling = phase as? Phase.Rolling
            Prompter(script, wpm, elapsedMs = rolling?.let { now - it.since } ?: 0L, bars = bars)
        }
        (phase as? Phase.Counting)?.let { c ->
            CaptureRules.countIn(now - c.since)?.let { n ->
                Text(
                    "$n", color = Color.White, fontSize = 120.sp,
                    modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "Starting in $n" }
                )
            }
        }
        // 24 dp more under the row than the bars ask: no reading of the insets
        // (safe drawing, the root view's, the activity's) kept the record button
        // off the moto's three-button bar inside this dialog window.
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bars).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp)) {
            failed?.let {
                Text(it, color = SquishColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (!withSound) {
                Text("No microphone allowed - this take will be silent.", color = SquishColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (phase is Phase.Idle) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 8.dp)) {
                    SelectableChip(label = "3-2-1", selected = countIn, accentColor = SquishColors.Primary) { countIn = !countIn }
                    SelectableChip(label = "9:16 guide", selected = guide, accentColor = SquishColors.Primary) { guide = !guide }
                    SelectableChip(label = "Teleprompter", selected = prompter, accentColor = SquishColors.Primary) {
                        prompter = !prompter
                        if (prompter && script.isBlank()) editingScript = true
                    }
                    // The phone's own camera app, for what this one cannot do (a lens it
                    // does not offer, a mode of the maker's): a chip, not a line under the
                    // button, which ran into the navigation bar.
                    SelectableChip(label = "Phone camera", selected = false, accentColor = SquishColors.Primary) { onPhoneCamera() }
                }
                if (prompter) {
                    LabeledSlider(
                        "Reading pace", wpm.toFloat(), CaptureRules.MIN_WPM.toFloat()..CaptureRules.MAX_WPM.toFloat(),
                        readout = { "${it.toInt()} words a minute" }
                    ) { wpm = it.toInt(); Script.savePace(context, wpm) }
                    SquishOutlinedButton("Edit the script", modifier = Modifier.fillMaxWidth()) { editingScript = true }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                IconButton(onClick = { stop(); onClose() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close the camera", tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                RecordButton(
                    rolling = phase !is Phase.Idle,
                    label = (phase as? Phase.Rolling)?.let { CaptureRules.clock(now - it.since) }
                ) {
                    when (phase) {
                        is Phase.Idle -> {
                            failed = null
                            if (countIn) phase = Phase.Counting(System.currentTimeMillis())
                            else {
                                phase = Phase.Rolling(System.currentTimeMillis())
                                start(context, capture, withSound, newFile()) { r, f -> recording = r; file = f }
                                    ?.let { failed = it; phase = Phase.Idle }
                            }
                        }
                        // The count-in cancelled: no file was opened.
                        is Phase.Counting -> phase = Phase.Idle
                        is Phase.Rolling -> stop()
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { if (phase is Phase.Idle) front = !front }, enabled = phase is Phase.Idle, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = if (front) "Use the back camera" else "Use the front camera", tint = Color.White)
                }
            }
        }
        if (editingScript) {
            ScriptEditor(script, bars, onDone = { text ->
                script = text
                Script.save(context, text)
                editingScript = false
            })
        }
    }
}

/** The script on a dark band over the top of the picture, moving up at the reading pace once the take rolls. */
@Composable
private fun Prompter(script: String, wpm: Int, elapsedMs: Long, bars: androidx.compose.foundation.layout.PaddingValues) {
    val density = LocalDensity.current
    var lines by remember { mutableStateOf(1) }
    var lineHeight by remember { mutableStateOf(1f) }
    val words = remember(script) { CaptureRules.wordsIn(script) }
    val scrolled = CaptureRules.scrollPx(elapsedMs, wpm, words, lines, lineHeight)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = bars.calculateTopPadding())
            .height(220.dp)
            .background(Color.Black.copy(alpha = 0.55f))
            .clip(RoundedCornerShape(0.dp))
    ) {
        Text(
            script,
            color = Color.White,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
            onTextLayout = { layout ->
                lines = layout.lineCount
                lineHeight = if (layout.lineCount > 0) layout.size.height.toFloat() / layout.lineCount else 1f
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // The line being read sits a third of the way down the band.
                .offset(y = with(density) { (70.dp.toPx() - scrolled).toDp() })
        )
    }
}

@Composable
private fun GuideFrame() {
    Box(modifier = Modifier.fillMaxSize().drawBehind {
        val g = CaptureRules.guide(size.width, size.height)
        // Dim what a 9:16 post leaves out, and outline what it keeps.
        val dim = Color.Black.copy(alpha = 0.35f)
        if (g.left > 0f) {
            drawRect(dim, Offset.Zero, Size(g.left, size.height))
            drawRect(dim, Offset(g.left + g.width, 0f), Size(size.width - g.left - g.width, size.height))
        }
        if (g.top > 0f) {
            drawRect(dim, Offset.Zero, Size(size.width, g.top))
            drawRect(dim, Offset(0f, g.top + g.height), Size(size.width, size.height - g.top - g.height))
        }
        drawRect(Color.White.copy(alpha = 0.7f), Offset(g.left, g.top), Size(g.width, g.height), style = Stroke(width = 2.dp.toPx()))
    })
}

@Composable
private fun RecordButton(rolling: Boolean, label: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .border(BorderStroke(4.dp, Color.White), CircleShape)
                .padding(8.dp)
                .clip(if (rolling) RoundedCornerShape(10.dp) else CircleShape)
                .background(SquishColors.Danger)
                .clickable(onClick = onClick)
                .semantics { contentDescription = if (rolling) "Stop recording" else "Start recording" }
        ) {}
        Text(label ?: "", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ScriptEditor(initial: String, bars: androidx.compose.foundation.layout.PaddingValues, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(SquishColors.Background.copy(alpha = 0.96f)).padding(bars).padding(16.dp)) {
        val tall = maxHeight
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Done at the top, beside the title: under the field it sat behind the keyboard.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Script", color = SquishColors.TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SquishPrimaryButton("Done") { onDone(text) }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("What to say, word for word") },
                modifier = Modifier.fillMaxWidth().height(tall * 0.4f)
            )
        }
    }
}

/** The last script and pace, kept for the next take (SharedPreferences "camera"). */
private object Script {
    private fun prefs(context: Context) = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    fun load(context: Context): String = prefs(context).getString("script", "").orEmpty()
    fun save(context: Context, text: String) = prefs(context).edit().putString("script", text).apply()
    fun pace(context: Context): Int = prefs(context).getInt("wpm", CaptureRules.DEFAULT_WPM)
    fun savePace(context: Context, wpm: Int) = prefs(context).edit().putInt("wpm", wpm).apply()
}

/** Each take as it is finalised: the file and an error to show, or null. */
private val finished = kotlinx.coroutines.flow.MutableSharedFlow<Pair<File, String?>>(extraBufferCapacity = 4)

/** Starts a take into [file]; null when it started, else the sentence to show. */
@SuppressLint("MissingPermission")
private fun start(
    context: Context,
    capture: VideoCapture<Recorder>,
    withSound: Boolean,
    file: File,
    started: (Recording, File) -> Unit
): String? = runCatching {
    val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        .let { if (withSound) it.withAudioEnabled() else it }
    val recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
        if (event is VideoRecordEvent.Finalize) {
            val error = when {
                !event.hasError() -> null
                event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "The phone ran out of space - the take stopped."
                event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> null // stopped by leaving: the file is whole
                else -> "The take could not be saved."
            }
            finished.tryEmit(file to error)
        }
    }
    started(recording, file)
    null
}.getOrElse { "The camera would not start recording." }

private suspend fun cameraProvider(context: Context): ProcessCameraProvider? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull() }
