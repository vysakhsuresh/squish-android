package com.squish.app.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.squish.app.home.countOf
import com.squish.app.home.formatSize
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.media.ExportStage
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.components.ExportProgressCard
import com.squish.app.ui.components.OutputSizePicker
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.components.StopExportDialog
import com.squish.app.ui.theme.SquishColors

/**
 * What gets written, asked before anything is written.
 *
 * Export used to fire straight off the header button at whatever the settings
 * happened to be, which meant the one irreversible, minutes-long action in the app
 * was also the only one with no confirmation. Now it opens here: resolution, frame
 * rate, quality, codec, colour, size limit, sound only, what it will weigh, and
 * then a button that says render.
 *
 * Original is the default. Someone who opened the editor came to edit, not to
 * shrink - silently re-encoding their footage smaller than they shot it is a
 * decision the app does not get to make on its own. What the last export was set
 * to is the starting point of the next project (Preferences).
 *
 * The rows are standard: a label, a line of chips or a switch, and a line under
 * it saying what the choice does, in the order CapCut asks them. Everything that
 * can appear or grow sits above the button, and the rows scroll when there are
 * more of them than the screen has room for; the button never leaves the bottom.
 */
@Composable
fun ExportSheet(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
    /** Where a finished file goes: the done screen. */
    onExported: (String) -> Unit
) {
    val context = LocalContext.current
    val render = { viewModel.export(onResult = onExported) }
    // The progress notification wants asking for from Android 13 on; refused,
    // the export runs the same with none. Asked on the first Render rather than
    // on opening the app, where a permission nobody has a reason for yet is
    // refused out of hand.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    val startRender = {
        val wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (wanted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else render()
    }

    var confirmStop by remember { mutableStateOf(false) }
    // An export that finishes while the question is open takes it with it.
    LaunchedEffect(state.isExporting) { if (!state.isExporting) confirmStop = false }

    // A frame of what is being written, for the card: the first shot's first
    // frame. Read once, when the render starts.
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(state.isExporting) {
        if (!state.isExporting || cover != null) return@LaunchedEffect
        val first = state.videoClips.filter { !it.isOverlay }.minByOrNull { it.timelineStartMs }
        val uri = first?.uri ?: state.sourceUri ?: return@LaunchedEffect
        cover = ThumbnailExtractor.frameAt(context, uri, first?.sourceInMs ?: state.trimStartMs)?.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize().zIndex(10f)) {
        // The scrim swallows taps so nothing behind the sheet can be nudged while
        // it is up, and dismisses - except mid-render, when leaving would strand
        // an encode with nothing on screen reporting it, and while an oversize
        // file waits for its answer.
        val holding = state.isExporting || state.fitOvershoot != null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SquishColors.Background.copy(alpha = 0.82f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !holding,
                    onClick = onDismiss
                )
        )

        val entry = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) { entry.targetState = true }

        AnimatedVisibility(
            visibleState = entry,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // Capped short of the top, so the header behind it can still be seen
            // to be there; what does not fit scrolls, and the button stays put.
            // While the settings show, the sheet is that height whatever is on
            // it: it is anchored to the bottom, so a row growing - a hint going
            // to two lines, the Fit chips appearing - pushed every row above it
            // up under the finger, and a second tap landed a row off. The
            // progress and overshoot cards, with nothing to tap twice, fit
            // themselves.
            val cap = LocalConfiguration.current.screenHeightDp.dp * 0.88f
            val settings = !state.isExporting && state.fitOvershoot == null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (settings) Modifier.height(cap) else Modifier.heightIn(max = cap))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(SquishColors.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SquishColors.Border)
                )

                if (state.isExporting) {
                    ExportProgressCard(
                        progress = state.exportProgress,
                        accent = SquishColors.Blue,
                        cover = cover,
                        onCancel = { confirmStop = true }
                    )
                    return@Column
                }

                state.fitOvershoot?.let { missed ->
                    OvershootCard(
                        actualBytes = missed.actualBytes,
                        targetBytes = missed.targetBytes,
                        onKeep = { viewModel.keepOversize(onExported) },
                        onRetry = { viewModel.retryFit(onExported) }
                    )
                    return@Column
                }

                SectionHeading(
                    title = "Export",
                    subtitle = "${Timecode.format(state.trimmedDurationMs)} · " +
                        "${countOf(state.videoClips.size, "clip")} · ${countOf(state.audioClips.size, "sound")}",
                    icon = Icons.Filled.FileUpload,
                    accent = SquishColors.Blue,
                    trailing = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = SquishColors.TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!state.audioOnly) {
                        PictureRows(state, viewModel)

                        // With the picture: a fit changes nothing about an
                        // .m4a, whose sound is at one fixed rate.
                        SwitchRow(
                            title = "Fit to a size",
                            subtitle = "For a strict upload limit. The size and the bitrate are chosen to land under it.",
                            checked = state.fitToSize,
                            onCheckedChange = viewModel::setFitToSize
                        )
                        if (state.fitToSize) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(16, 25, 50, 100).forEach { mb ->
                                    SelectableChip(
                                        label = "$mb MB",
                                        selected = state.targetSizeMb == mb,
                                        accentColor = SquishColors.Cyan,
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.setTargetSizeMb(mb) }
                                    )
                                }
                            }
                        }
                    }

                    SwitchRow(
                        title = "Sound only",
                        subtitle = "An .m4a of the mix - camera, music and voice - with no picture. Saved to Music.",
                        checked = state.audioOnly,
                        onCheckedChange = viewModel::setAudioOnly
                    )

                    // Said here, where Render is, and not only on the card shown
                    // when the file was opened: that one is dismissed and gone.
                    SquishError.soundLeftOut(state)?.let { codec ->
                        Text(
                            "Camera sound left out: $codec isn't supported on this phone. Music and voiceovers are kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.Amber
                        )
                    }

                    // A photo still being made into a clip is not on the timeline yet;
                    // rendering now would leave it out of the file.
                    if (state.preparingStills > 0) {
                        Text(
                            "Preparing ${countOf(state.preparingStills, "photo")} for the timeline - " +
                                "Render is ready when they are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.Amber
                        )
                    }
                }

                SquishPrimaryButton(
                    text = if (state.audioOnly) "Render the sound" else "Render and save",
                    enabled = !state.isLoadingSource && state.preparingStills == 0,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startRender
                )
            }
        }
    }

    if (confirmStop) {
        StopExportDialog(
            saving = state.exportProgress.stage == ExportStage.Saving,
            onStop = {
                // Nothing to stop means the encode finished under the tap; the
                // dialog stays and now says the copy is under way.
                if (viewModel.cancelExport()) confirmStop = false
            },
            onKeepGoing = { confirmStop = false }
        )
    }
}

/** The rows about the picture: size, rate, quality, codec and colour. None of them matter to a sound-only file. */
@Composable
private fun PictureRows(state: EditorUiState, viewModel: EditorViewModel) {
    // The frame the file is cut to - rotated and cropped - not the one the
    // camera shot. After a quarter turn the sheet said 1920 × 1080 for a file
    // that came out 1080 × 1920, and after a crop it promised the whole frame.
    // And the size the encoder will write for it, asked as the choice changes:
    // 4K was promised and written at half the size without a word. Asked again
    // when the codec changes, whose encoder may stop at a different size.
    LaunchedEffect(state.outputResolution, state.exportCodecHevc) { viewModel.probeEncoder() }
    val fps = state.exportFps
    val fpsLabel = if (fps % 1f > 0.05f) "%.2f fps".format(fps) else "%.0f fps".format(fps)
    val codecLabel = if (state.exportCodecHevc) "HEVC" else "H.264"
    // Where it is going, in one tap: each sets the size, the limit and the
    // quality that place wants, and every row below still shows and changes it.
    Text("For", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        SharePreset.entries.forEach { preset ->
            SelectableChip(
                label = preset.label,
                selected = preset.matches(state),
                accentColor = SquishColors.Blue,
                onClick = { preset.apply(viewModel) }
            )
        }
    }
    OutputSizePicker(
        outputP = state.outputP,
        fitToSize = state.fitToSize,
        sourceWidth = state.croppedFrame.width,
        sourceHeight = state.croppedFrame.height,
        asked = state.outputResolution,
        written = state.writtenResolution,
        estimatedBytes = state.estimatedOutputBytes,
        // "Smaller than the original" only where there is one original: a
        // cut of the source file. It is that one file's size, so beside a
        // photo it read "33% bigger" of a 3 s picture, and beside several
        // videos it measured against the first alone.
        originalBytes = if (
            state.videoClips.none { it.isOverlay } &&
            state.videoClips.filter { it.isMain }.let { shots -> shots.isNotEmpty() && shots.all { it.isFootage && (it.uri == null || it.uri == state.sourceUri) } }
        ) state.originalSizeBytes else 0L,
        accent = SquishColors.Blue,
        onPick = viewModel::setOutputP,
        ceilingP = state.encoderCeilingP,
        detail = "$fpsLabel  ·  $codecLabel"
    )

    val sourceFps = state.fps
    ChoiceRow(
        label = "Frame rate",
        hint = when {
            ExportSettings.exceedsSource(state.outputFps, sourceFps) ->
                "The footage runs at ${"%.0f".format(sourceFps)} fps. A higher rate can't add frames; the file keeps the ones it has."
            state.outputFps == ExportSettings.SOURCE_FPS ->
                if (sourceFps > 1f) "Auto keeps the footage's ${"%.0f".format(sourceFps)} fps." else "Auto keeps the footage's own rate."
            // The rate asked for is the rate the footage runs at, so nothing is
            // dropped. The line below said frames were, which is the sheet
            // describing an edit it is not making - 30 chosen on 30 fps footage
            // is the commonest case there is.
            sourceFps > 1f && kotlin.math.abs(state.outputFps - sourceFps) <= 0.5f ->
                "The footage already runs at ${"%.0f".format(sourceFps)} fps, so every frame is kept."
            else -> "Frames are dropped to reach it. 24 or 25 for a film look; 30 for a smaller file."
        },
        options = listOf("Auto" to ExportSettings.SOURCE_FPS) + ExportSettings.FPS_CHOICES.map { "$it" to it },
        selected = state.outputFps,
        accent = SquishColors.Blue,
        onPick = viewModel::setOutputFps
    )

    if (!state.fitToSize) {
        ChoiceRow(
            label = "Quality",
            hint = when (state.quality) {
                ExportQuality.Lower -> "About the bits a phone spends on a message-sized copy: smaller, a little softer."
                ExportQuality.Recommended -> "Recommended: the same bits per pixel the footage was shot at."
                ExportQuality.Higher -> "More bits than the footage was shot at: no softer, and a bigger file."
            },
            options = ExportQuality.entries.map { it.label to it },
            selected = state.quality,
            accent = SquishColors.Blue,
            onPick = viewModel::setQuality
        )
    }

    // Offered only where the phone has the encoder; asked of one that does
    // not, Media3 stops at the start of the render.
    if (state.hevcAvailable == true) {
        // Locked on only when the render will in fact keep HDR - not on a
        // layered edit, where the switch below says it is converted.
        val forHdr = state.effectiveKeepHdr
        SwitchRow(
            title = if (state.fitToSize) "Sharper file (HEVC)" else "Smaller file (HEVC)",
            subtitle = when {
                forHdr -> "Needed to keep HDR."
                // In fit mode the bits are the budget, so HEVC cannot make the
                // file smaller; it spends the same bits better.
                state.fitToSize -> "The same size, with a better picture in it. Plays on phones from 2015 on; some older computers need a codec."
                else -> "About a third smaller at the same quality. Plays on phones from 2015 on; some older computers need a codec."
            },
            checked = state.exportCodecHevc,
            enabled = !forHdr,
            onCheckedChange = viewModel::setHevc
        )
    }

    if (state.hasHdrSource) {
        // A layered export is always written in ordinary colour: its first
        // input is the clock still, which sets the file's colour
        // (CompositionFactory). The switch says so rather than promising.
        val layered = state.isLayered
        SwitchRow(
            title = "Keep HDR",
            subtitle = when {
                layered -> "Transitions, overlays and gaps are written in ordinary colour, which every screen shows the same."
                state.keepHdr -> if (state.hevcAvailable == true) "The HDR is kept, in HEVC. Ordinary screens show it dimmer."
                else "Kept where this phone's encoder can write it; converted otherwise."
                else -> "Converted to ordinary colour, which every screen shows the same. On, the file keeps its HDR."
            },
            checked = state.keepHdr && !layered,
            enabled = !layered,
            onCheckedChange = viewModel::setKeepHdr
        )
    }
}

/** A label, a line of chips, and what the chosen one does. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    hint: String?,
    options: List<Pair<String, T>>,
    selected: T,
    accent: Color,
    onPick: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
        // Rows of three past four: six in one row (the frame rates) left
        // "Auto" about 28dp of room on a 360dp phone.
        options.chunked(if (options.size > 4) 3 else options.size.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (name, value) ->
                    SelectableChip(
                        label = name,
                        selected = value == selected,
                        accentColor = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(value) }
                    )
                }
            }
        }
        if (hint != null) {
            // Two lines' room whatever the hint says, so a chip tapped does not
            // move the row when its hint changes length.
            Text(hint, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted, minLines = 2)
        }
    }
}

/** A title, what the switch does, and the switch. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            // Two lines' room, as a chip row's hint has: a switch whose words
            // change with it does not move the rows above.
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted, minLines = 2)
        }
        SquishToggleSwitch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) }
        )
    }
}

/**
 * A fitted export that came out over its limit. The file is whole and saved;
 * the question is whether to keep it as it is or run once more, aimed lower.
 */
@Composable
private fun OvershootCard(actualBytes: Long, targetBytes: Long, onKeep: () -> Unit, onRetry: () -> Unit) {
    SquishCard(accent = SquishColors.Amber) {
        Text(
            "Came out at ${formatSize(actualBytes)} - over the ${formatSize(targetBytes)} limit",
            style = MaterialTheme.typography.titleSmall,
            color = SquishColors.TextPrimary
        )
        Text(
            "Encoders overshoot on busy footage. This file is saved in your gallery as it is. " +
                "A second run aims lower and should land under the limit.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )
        // Stacked: side by side at half width "Try again, tighter" was cut short.
        SquishPrimaryButton(
            text = "Try again, tighter",
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetry
        )
        SquishOutlinedButton(
            text = "Keep this one",
            modifier = Modifier.fillMaxWidth(),
            onClick = onKeep
        )
    }
}

/**
 * Where a video is going, as the settings that place wants: WhatsApp's 16 MB
 * attachment limit and email's 10, Reels and TikTok at 1080p, YouTube at the
 * footage's own size and higher quality.
 */
private enum class SharePreset(val label: String, val outputP: Int, val fitMb: Int?, val quality: ExportQuality) {
    WhatsApp("WhatsApp", OutputSize.ORIGINAL, 16, ExportQuality.Recommended),
    Email("Email", OutputSize.ORIGINAL, 10, ExportQuality.Recommended),
    Reels("Reels · TikTok", 1080, null, ExportQuality.Recommended),
    YouTube("YouTube", OutputSize.ORIGINAL, null, ExportQuality.Higher);

    fun matches(state: EditorUiState): Boolean =
        if (fitMb != null) state.fitToSize && state.targetSizeMb == fitMb
        else !state.fitToSize && state.outputP == outputP && state.quality == quality

    fun apply(viewModel: EditorViewModel) {
        if (fitMb != null) {
            viewModel.setTargetSizeMb(fitMb)
            viewModel.setFitToSize(true)
        } else {
            viewModel.setOutputP(outputP)
        }
        viewModel.setQuality(quality)
    }
}
