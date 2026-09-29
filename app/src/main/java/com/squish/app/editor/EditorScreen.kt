package com.squish.app.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.home.countOf
import com.squish.app.media.ExportStage
import com.squish.app.media.keepReadAccess
import com.squish.app.timeline.TimelineActionBar
import com.squish.app.timeline.TimelineEditor
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.rippleVideo
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.StopExportDialog
import com.squish.app.ui.theme.SquishColors

/**
 * The editor, laid out as docs/ROADMAP.md section 2 decided:
 *
 * a header with the project's name, undo, redo and Export; the picture, taking
 * whatever height is left, with its transport under it; the strip, always on
 * screen; Split, Duplicate and Delete under the strip; and one row of tools -
 * the ones that add things with nothing selected, the selection's own with
 * something selected. A tool opens as a sheet in the tools' place, and the strip
 * shrinks to its ruler and one row above it rather than folding away.
 *
 * It used to be eleven tabs that never changed with the selection, ten of which
 * opened with the strip - and undo, split and the timecode with it - folded out
 * of sight, and back left the editor from inside any of them.
 */
@Composable
fun EditorScreen(
    sourceUri: Uri,
    /** Opened from the drafts list: apply the saved edit straight away. */
    resume: Boolean = false,
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Which tool's sheet is open, by name so it survives a trip to the photo
    // picker or the app being put away. None, to begin with: what matters on
    // arrival is the picture and the strip.
    var openToolName by rememberSaveable { mutableStateOf<String?>(null) }
    val openTool = openToolName?.let { name -> Tool.entries.firstOrNull { it.name == name } }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // The line Add text just made, until it has words or is let go of. Let go of
    // blank - Done, back, another selection - it is taken off again: a line with
    // no words draws nothing, and sat on the strip as an empty bar.
    var newLineId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openTool, state.selectedClipId) {
        val id = newLineId ?: return@LaunchedEffect
        if (openTool == Tool.Edit && state.selectedClipId == id) return@LaunchedEffect
        newLineId = null
        viewModel.text.discardIfBlank(id)
    }

    var exportSheetOpen by rememberSaveable { mutableStateOf(false) }
    // A failure is explained by a card under the picture, which the sheet covers.
    // Left open, "Render and save" looked as if it did nothing at all.
    LaunchedEffect(state.failure) { if (state.failure != null) exportSheetOpen = false }
    var confirmStopExport by remember { mutableStateOf(false) }
    var confirmStartNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // A photo overlay waiting on "make it a long clip?" before it goes to the main track.
    var confirmLongStill by remember { mutableStateOf<String?>(null) }
    // A finger on the playhead, told to the preview so it can serve the drag from
    // sync samples and land exactly when the finger lifts.
    var timelineScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri, resume) }

    val kind = state.selectionKind
    val canTransition = state.selectedCanTransition
    // Only a draft from before the main track was magnetic can hold a gap; the
    // way to close one appears when there is one, and not otherwise.
    val mainTrackHasGaps = remember(state.videoClips) {
        val track = TimelineState(clips = state.videoClips)
        track.rippleVideo().baseVideoClips.map { it.timelineStartMs } != track.baseVideoClips.map { it.timelineStartMs }
    }
    // A clip's own tool closes when the selection no longer has it - Speed with
    // a caption selected, anything once the selection is deleted or let go.
    LaunchedEffect(openTool, kind, canTransition) {
        val tool = openTool ?: return@LaunchedEffect
        if (!sheetSurvives(tool, kind, canTransition)) openToolName = null
    }

    // Leaving flushes the edit first, so the last thing done before back is on
    // disk rather than in the ticker's gap. And mid-export, leaving is a
    // question: the back gesture used to pop the screen, clear the view model
    // and cancel the encode with nothing said and a broken file left behind.
    val leave = {
        if (state.isExporting) confirmStopExport = true
        else {
            newLineId?.let(viewModel.text::discardIfBlank)
            newLineId = null
            viewModel.saveNow()
            onBack()
        }
    }
    // Innermost first - see backStep. An export in progress outranks all of it.
    BackHandler {
        when {
            state.isExporting -> confirmStopExport = true
            exportSheetOpen -> exportSheetOpen = false
            else -> when (backStep(fullscreen, openTool != null, state.selectedClipId != null)) {
                BackStep.ExitFullscreen -> fullscreen = false
                BackStep.CloseSheet -> openToolName = null
                BackStep.Deselect -> viewModel.selectClip(null)
                BackStep.Leave -> leave()
            }
        }
    }
    // The app going behind something - a call, the home button - is the moment
    // it is most likely to be killed, so the edit is flushed there too.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveNow() }

    // An encode only runs while the app is in front, so the screen must not go
    // to sleep under a long one.
    val view = LocalView.current
    DisposableEffect(state.isExporting) {
        view.keepScreenOn = state.isExporting
        onDispose { view.keepScreenOn = false }
    }
    // The keyboard is made room for here, by the layout (see imePadding below),
    // rather than by the window sliding up: panned, the header and the picture
    // went off the top, so the line being typed could not be seen on the
    // picture - and the window drawing edge to edge meant it might not pan at
    // all and the keyboard sat over the field. Only while the editor is up;
    // every other screen keeps the window's own behaviour.
    DisposableEffect(view) {
        val window = view.context.findWindow()
        val before = window?.attributes?.softInputMode
        @Suppress("DEPRECATION")
        window?.setSoftInputMode(
            (before ?: 0) and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        onDispose { if (before != null) window.setSoftInputMode(before) }
    }
    // An export that finishes while "Stop exporting?" is open takes the question
    // with it: there is nothing left to stop, and a Stop tapped then looked as if
    // it had thrown away a file that was already in the gallery.
    LaunchedEffect(state.isExporting) { if (!state.isExporting) confirmStopExport = false }

    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.audio.addAudioTrack(it)
        }
    }
    // Several at once, added in the order picked - one video at a time made
    // building an edit from a handful of shots a chore.
    val pickExtraClips = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.clips.addVideoClips(uris)
    }
    val addVideos = {
        pickExtraClips.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }
    // Photos and videos, several at once: a logo, a cut-out and a reaction clip
    // are all overlays. It was one video at a time.
    val pickOverlayClips = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.layers.addOverlayClips(uris)
    }
    val addOverlay = {
        pickOverlayClips.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }
    val addText = {
        newLineId = viewModel.text.addCaptionAtPlayhead()
        openToolName = Tool.Edit.name
    }

    val onTool: (Tool) -> Unit = { tool ->
        when (tool) {
            Tool.Clip -> {
                val shots = state.videoClips.filter { it.isMain }
                    .map { ShotSpan(it.id, it.timelineStartMs, it.timelineEndMs) }
                cutTarget(shots, state.playheadMs)?.let(viewModel::selectClip)
            }
            Tool.Overlay -> addOverlay()
            Tool.Split -> viewModel.clips.splitAtPlayhead()
            Tool.Duplicate -> viewModel.clips.duplicateSelected()
            Tool.Delete -> viewModel.clips.deleteSelectedClip()
            Tool.ToOverlay -> state.selectedClipId?.let(viewModel.layers::switchToOverlay)
            Tool.ToMain -> state.videoClips.firstOrNull { it.id == state.selectedClipId }?.let { clip ->
                // A photo dragged out long is minutes of rendering on the main
                // track; asked about rather than started with a spinner.
                if (OverlayRules.needsLongRender(clip)) confirmLongStill = clip.id
                else viewModel.layers.switchToMain(clip.id)
            }
            else -> if (tool.sheet) openToolName = tool.name
        }
    }

    // A clip tapped on the strip while an add-things sheet is open is a new job:
    // the sheet goes and the clip's own tools come up. A selection the sheet
    // made itself - a sticker it just added - is left alone.
    val selectFromStrip: (String?) -> Unit = { id ->
        val before = state.selectedClipId
        viewModel.selectClip(id)
        if (id != null && id != before && openTool?.levelZero == true) openToolName = null
    }

    // The picture, its transport and everything drawn over it. Movable, so a
    // rotation that lays the screen out in two panes carries the same players
    // across instead of building new ones and loading every clip again.
    val preview = remember {
        movableContentOf { modifier: Modifier ->
            EditorPreview(
                state = state,
                viewModel = viewModel,
                sourceUri = sourceUri,
                // Read here, not captured: this lambda is remembered once, and a
                // value worked out outside it would stay whatever it first was.
                openTool = openToolName?.let { name -> Tool.entries.firstOrNull { it.name == name } },
                fullscreen = fullscreen,
                timelineScrubbing = timelineScrubbing,
                onFullscreen = { fullscreen = it },
                onCloseSheet = { openToolName = null },
                onOpenTool = { tool -> openToolName = tool.name },
                modifier = modifier
            )
        }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        // Which way up is decided before the keyboard takes its share: a short
        // phone with the keyboard up is not a phone on its side, and switching
        // layouts mid-word would rebuild the sheet being typed in.
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val landscape = maxWidth > maxHeight
            BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
                val available = maxHeight
                val sheetHeight = sheetHeightFor(available)
                val sheetOpen = openTool != null
                // The keyboard is up - for a line of text, a search, a size. The strip
                // and the notices fold away until it goes (kept composed, so the strip
                // comes back scrolled where it was), and the sheet sits on the
                // keyboard with the picture above it, where the words being typed
                // can be seen landing.
                val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val cardsScroll = rememberScrollState()
                val stripScroll = rememberScrollState()

                val header = @Composable {
                    EditorHeader(
                        state = state,
                        onBack = leave,
                        // Not while a saved edit is on offer - see renameProject.
                        onRename = if (state.recovery == null) ({ renaming = true }) else null,
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        onExport = { exportSheetOpen = true }
                    )
                }
                // Beside the strip, never in its column: in there a failure or a
                // banner pushed the compressed strip out of its capped height, and
                // the strip is the one thing section 2 says never goes.
                val cards = @Composable {
                    StatusCards(state, viewModel, onStartNew = { confirmStartNew = true })
                }
                val controls = @Composable { compactStrip: Boolean ->
                    val timeline = state.toTimeline()
                    TimelineEditor(
                        state = timeline,
                        onSelect = selectFromStrip,
                        onMoveTo = viewModel.clips::moveClipTo,
                        onTrim = viewModel.clips::trimClip,
                        onScrub = viewModel::scrubTo,
                        onTransitionTap = { clipId ->
                            viewModel.selectClip(clipId)
                            openToolName = Tool.Transition.name
                        },
                        markers = state.markers,
                        barMarkers = state.beats.every(4),
                        isPlaying = state.isPlaying,
                        fitNonce = state.fitNonce,
                        onZoomTo = viewModel::setPixelsPerSecond,
                        onEffectMove = viewModel.clips::moveEffect,
                        onEffectTrim = viewModel.clips::trimEffect,
                        onAddVideo = addVideos,
                        onAddBlank = viewModel.clips::addBlankClip,
                        onAddOverlay = addOverlay,
                        onOpenSound = { openToolName = Tool.Sound.name },
                        onOpenWords = { openToolName = Tool.Text.name },
                        onScrubbingChange = { timelineScrubbing = it },
                        compact = compactStrip,
                        onFit = viewModel::fitTimeline
                    )
                    TimelineActionBar(
                        state = timeline,
                        onSplit = viewModel.clips::splitAtPlayhead,
                        onDelete = viewModel.clips::deleteSelectedClip,
                        onDuplicate = viewModel.clips::duplicateSelected,
                        toolbarHasThem = !sheetOpen && kind != SelectionKind.None,
                        accent = kind.concept?.accent ?: Concept.Video.accent,
                        effectSelected = kind == SelectionKind.Effect,
                        splittable = state.canSplitHere,
                        onCloseGaps = if (mainTrackHasGaps) viewModel.clips::closeGaps else null,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                val toolbar = @Composable {
                    val accent = kind.concept?.accent
                    ToolBar(
                        tools = toolsFor(kind, canTransition),
                        accentOf = { tool -> accent ?: tool.levelZeroAccent },
                        onTool = onTool,
                        enabled = { tool ->
                            when (tool) {
                                Tool.Clip -> state.videoClips.any { it.isMain }
                                Tool.Split -> state.canSplitHere
                                else -> true
                            }
                        },
                        onBack = if (kind == SelectionKind.None) null else ({ viewModel.selectClip(null) }),
                        backAccent = accent ?: SquishColors.TextSecondary
                    )
                }
                val sheet = @Composable { modifier: Modifier ->
                    openTool?.let { tool ->
                        EditorToolSheet(
                            tool = tool,
                            state = state,
                            viewModel = viewModel,
                            onDone = { openToolName = null },
                            onPickAudio = { pickAudioTrack.launch(arrayOf("audio/*", "video/*")) },
                            onAddText = addText,
                            onSelectSound = { id ->
                                viewModel.selectClip(id)
                                openToolName = null
                            },
                            modifier = modifier
                        )
                    }
                }

                when {
                    state.isLoadingSource -> Column(modifier = Modifier.fillMaxSize()) {
                        header()
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SquishColors.Primary)
                        }
                    }

                    // The picture and its transport, and nothing else. Back, or the
                    // transport's own button, returns.
                    fullscreen -> preview(Modifier.fillMaxSize())

                    // A phone on its side has no height to stack everything in, so
                    // the picture takes the left half and the tools the right.
                    landscape -> Row(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f)) {
                            header()
                            preview(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = if (typing) 0.dp else available * CARDS_SHARE)
                                    .verticalScroll(cardsScroll)
                            ) {
                                cards()
                            }
                            // One place for the strip whether a sheet is open or not, so
                            // opening one does not build a new strip scrolled to the start.
                            // It takes what the sheet leaves: the sheet's height is fixed
                            // first, so a notice or a long strip can never squeeze it -
                            // and its Done - down to nothing.
                            Column(
                                modifier = (if (typing) Modifier.heightIn(max = 0.dp) else Modifier.weight(1f))
                                    .verticalScroll(stripScroll)
                            ) {
                                controls(sheetOpen)
                            }
                            if (sheetOpen) {
                                sheet(if (typing) Modifier.weight(1f) else Modifier.height(available * LANDSCAPE_SHEET_SHARE))
                            } else toolbar()
                        }
                    }

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        header()
                        preview(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp))
                        // Never more than their share, however many rows and notices
                        // there are: past it they scroll, and the picture keeps a size
                        // worth looking at.
                        Column(
                            modifier = Modifier
                                .heightIn(max = if (typing) 0.dp else available * CARDS_SHARE)
                                .verticalScroll(cardsScroll)
                        ) {
                            cards()
                        }
                        Column(
                            modifier = Modifier
                                .heightIn(
                                    max = when {
                                        typing -> 0.dp
                                        sheetOpen -> available * STRIP_SHARE_WITH_SHEET
                                        else -> available * STRIP_SHARE
                                    }
                                )
                                .verticalScroll(stripScroll)
                        ) {
                            controls(sheetOpen)
                        }
                        if (sheetOpen) {
                            sheet(Modifier.height(if (typing) typingSheetHeightFor(available) else sheetHeight))
                        } else toolbar()
                    }
                }

                if (exportSheetOpen) {
                    ExportSheet(
                        state = state,
                        viewModel = viewModel,
                        onDismiss = { exportSheetOpen = false },
                        onRender = {
                            viewModel.export(onResult = onExported)
                        }
                    )
                }
            }
        }
    }

    // After the app was killed under this edit the offer blocks the editor until
    // it is answered: nothing can be saved until it is known which edit this is,
    // and an inline card let the bare clip be edited for as long as anyone liked
    // with none of it reaching disk.
    state.recovery?.takeIf { it.modal }?.let { offer ->
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            RecoveryBanner(
                offer = offer,
                onContinue = viewModel::acceptRecovery,
                onStartNew = { confirmStartNew = true }
            )
        }
    }

    // "Start a new project" sets the saved edit aside; it never deletes it. But it
    // is still the button that makes hours of work disappear from the editor, so
    // it asks - the way discarding from the drafts list always has.
    if (confirmStartNew) {
        ConfirmDialog(
            title = "Start a new project?",
            body = "The saved edit of this clip is set aside so you can begin again from the untouched video.",
            caution = "It moves to Recently discarded on the Unfinished screen, where it can be brought back for 30 days.",
            confirmLabel = "Start new",
            dismissLabel = "Keep",
            icon = Icons.Filled.HistoryToggleOff,
            accent = SquishColors.Amber,
            onConfirm = {
                viewModel.dismissRecovery()
                confirmStartNew = false
            },
            onDismiss = { confirmStartNew = false }
        )
    }

    if (confirmStopExport) {
        StopExportDialog(
            saving = state.exportProgress.stage == ExportStage.Saving,
            onStop = {
                // Nothing to stop means the encode finished under the tap; the
                // dialog stays and now says the copy is under way.
                if (viewModel.cancelExport()) confirmStopExport = false
            },
            onKeepGoing = { confirmStopExport = false }
        )
    }

    confirmLongStill?.let { clipId ->
        val minutes = state.videoClips.firstOrNull { it.id == clipId }?.durationMs?.let { (it + 59_999) / 60_000 } ?: 1
        ConfirmDialog(
            title = "Make this photo a $minutes-minute clip?",
            body = "The main track plays video, so the photo is made into a clip as long as the overlay is. " +
                "That takes a while at this length - keep the editor open until it lands.",
            caution = "To keep it quick, shorten the overlay first.",
            confirmLabel = "Make clip",
            dismissLabel = "Not now",
            icon = Icons.Filled.HistoryToggleOff,
            accent = SquishColors.Amber,
            onConfirm = {
                viewModel.layers.switchToMain(clipId)
                confirmLongStill = null
            },
            onDismiss = { confirmLongStill = null }
        )
    }

    if (renaming) {
        RenameDialog(
            current = state.projectName.orEmpty(),
            placeholder = state.videoClips.firstOrNull()?.label ?: "Untitled edit",
            onSave = { name ->
                viewModel.renameProject(name)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }
}

/**
 * The sheet's share of the screen: enough to hold a tool without scrolling for
 * most of them, and never so much that the picture above is a strip of its own.
 */
private fun sheetHeightFor(available: Dp): Dp {
    val floor = minOf(SHEET_MIN, available * 0.5f)
    return (available * SHEET_SHARE).coerceIn(floor, maxOf(floor, SHEET_MAX))
}

/**
 * The sheet's height with the keyboard up: half of what the keyboard leaves,
 * so the picture keeps the other half and the words can be watched landing.
 */
private fun typingSheetHeightFor(available: Dp): Dp {
    val floor = minOf(TYPING_SHEET_MIN, available * 0.8f)
    return (available * 0.5f).coerceIn(floor, maxOf(floor, SHEET_MAX))
}

/** The window the editor is drawn in, for its keyboard behaviour. */
private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

/** The picture, and what is drawn over it for the tool that is open. */
@Composable
private fun EditorPreview(
    state: EditorUiState,
    viewModel: EditorViewModel,
    sourceUri: Uri,
    openTool: Tool?,
    fullscreen: Boolean,
    timelineScrubbing: Boolean,
    onFullscreen: (Boolean) -> Unit,
    /** Closes whatever sheet is open. */
    onCloseSheet: () -> Unit,
    /** Opens a tool's sheet. */
    onOpenTool: (Tool) -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (fullscreen) 0.dp else 14.dp))
            .background(SquishColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        // Auto-reframe, in the head clip's source time, and where it has the
        // crop centred at the playhead - for the outline drawn over the picture.
        val reframeOffset = state.videoClips.firstOrNull()?.let { it.sourceInMs - it.timelineStartMs } ?: 0L
        val reframeFocus = state.reframe?.takeIf { state.cropAspect.ratio != null }
            ?.sampleAt(state.playheadMs + reframeOffset)?.let { it.xFraction to it.yFraction }
        // The overlay box on the picture. Its moves go through the one way
        // placement is written, so a drag is one undo step and an animated
        // overlay is keyed at the playhead; each gesture ends its step.
        val latestOpenTool by rememberUpdatedState(openTool)
        val latestCloseSheet by rememberUpdatedState(onCloseSheet)
        val latestOpenToolSheet by rememberUpdatedState(onOpenTool)
        val boxActions = remember(viewModel) {
            OverlayHandleActions(
                // As a tap on the strip: a layer picked while an add-things sheet
                // is up is a new job, and the sheet makes way for its tools.
                onSelect = { id ->
                    val before = viewModel.state.value.selectedClipId
                    viewModel.selectClip(id)
                    if (id != before && latestOpenTool?.levelZero == true) latestCloseSheet()
                },
                onPlace = { id, t ->
                    viewModel.clips.setClipTransform(
                        id, scale = t.scale, offsetX = t.offsetXFraction, offsetY = t.offsetYFraction, rotation = t.rotationDegrees
                    )
                },
                onPlaceEnd = viewModel::endGesture,
                onDelete = { id ->
                    viewModel.selectClip(id)
                    viewModel.clips.deleteSelectedClip()
                },
                onDuplicate = viewModel.layers::duplicateInPlace,
                onEdit = { id ->
                    viewModel.selectClip(id)
                    latestOpenToolSheet(Tool.Placement)
                }
            )
        }
        // The hand-drawn crop is only taken hold of on the Frame sheet, where it
        // is the thing being worked on, and there it is modal: the overlay box
        // stands aside. Anywhere else it is drawn and left alone - it took every
        // touch on the picture whenever no overlay was selected, so an overlay
        // could not be tapped on the picture to select it.
        val editingCrop = state.cropAspect == CropAspect.Custom && openTool == Tool.Frame
        val overlayActions = boxActions.takeIf { !editingCrop }
        TimelinePreview(
            videoClips = state.videoClips,
            audioClips = state.audioClips,
            captions = state.textOverlays,
            effects = state.effects,
            fallbackUri = sourceUri,
            proxies = state.proxyUris,
            muteOriginal = state.muteOriginal,
            voiceEffect = state.voiceEffect,
            originalVolume = state.originalVolume,
            grade = state.grade,
            rotationDegrees = state.rotationDegrees,
            // The shape actually being kept, not the chosen ratio. A
            // hand-drawn crop has no ratio of its own, so passing the
            // enum's left the preview showing no crop at all until the
            // file came out the other end.
            cropRatio = state.previewCropRatio,
            // The rectangle itself, so captions sit where the export puts them.
            customCrop = state.cropRect.takeIf { state.cropAspect == CropAspect.Custom && !it.isFull },
            // Auto-reframe follows a fixed ratio's frame; a hand-drawn
            // rectangle is the frame, so the two do not combine.
            reframe = state.reframe.takeIf { state.cropAspect.ratio != null },
            reframeOffsetMs = reframeOffset,
            sourceAspect = state.sourceFrameAspect,
            playheadMs = state.playheadMs,
            scrubNonce = state.scrubNonce,
            onPositionChange = viewModel::setPlayhead,
            onPlayingChange = viewModel::setPlaying,
            onStep = viewModel::stepFrames,
            fullscreen = fullscreen,
            onToggleFullscreen = { onFullscreen(!fullscreen) },
            onScrub = viewModel::seekTo,
            onPictureTap = {
                // Empty picture lets go of the selection, the way bare track does;
                // with nothing selected, the picture is the play button.
                if (state.selectedClipId != null) {
                    viewModel.selectClip(null)
                    true
                } else false
            },
            scrubbing = timelineScrubbing,
            selectedClipId = state.selectedClipId,
            overlayActions = overlayActions,
            modifier = Modifier.fillMaxSize(),
            // Inside the picture, so the crop rectangle is measured
            // against the frame rather than against the whole box.
            pictureOverlay = {
                // Live framing while cropping, so the ratio is never
                // chosen blind - and with the part being cropped away
                // still on screen, dimmed, which is the only way to see
                // what a crop is actually costing. Not in full screen,
                // which is for watching.
                when {
                    fullscreen -> Unit
                    state.cropAspect == CropAspect.Custom -> CustomCropOverlay(
                        rect = state.cropRect,
                        editable = editingCrop,
                        // Live while dragging, recorded once at the end:
                        // the view model coalesces, so a gesture is one
                        // undo step rather than one per frame of movement.
                        onChange = viewModel.clips::setCropRect,
                        onCommit = { viewModel.clips.setCropRect(state.cropRect) },
                        modifier = Modifier.fillMaxSize()
                    )
                    openTool == Tool.Frame || state.cropAspect != CropAspect.Original ->
                        CropOverlay(
                            aspect = state.cropAspect,
                            focus = reframeFocus,
                            modifier = Modifier.fillMaxSize()
                        )
                }
            }
        )
    }
}

/**
 * Back, the project's name, undo and redo, Export. Undo and redo live here and
 * never leave the screen; they used to be in the strip's action bar, which ten
 * of the eleven tools folded away.
 */
@Composable
private fun EditorHeader(
    state: EditorUiState,
    onBack: () -> Unit,
    /** Null while the name cannot be changed - a saved edit is on offer; see renameProject. */
    onRename: (() -> Unit)?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BackOrb(accent = SquishColors.Violet, onClick = onBack, size = 40.dp)
        Spacer(modifier = Modifier.width(4.dp))
        // The name is the rename button: tap it. The pencil says so.
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .clickable(enabled = onRename != null, onClickLabel = "Rename the project", onClick = { onRename?.invoke() })
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.projectName ?: state.videoClips.firstOrNull()?.label ?: "Your edit",
                    style = MaterialTheme.typography.titleMedium,
                    color = SquishColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (onRename != null) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = SquishColors.TextMuted, modifier = Modifier.size(14.dp))
                }
            }
            Text(
                "${countOf(state.videoClips.size, "clip")} · ${Timecode.format(state.trimmedDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 1
            )
        }
        HeaderIcon(
            Icons.AutoMirrored.Filled.Undo,
            // What it will undo, for a screen reader - a bare arrow is a button
            // you press and then look at the screen to find out what changed.
            state.undoLabel?.let { "Undo: $it" } ?: "Nothing to undo",
            enabled = state.undoLabel != null,
            onClick = onUndo
        )
        HeaderIcon(
            Icons.AutoMirrored.Filled.Redo,
            state.redoLabel?.let { "Redo: $it" } ?: "Nothing to redo",
            enabled = state.redoLabel != null,
            onClick = onRedo
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Opens the sheet rather than firing: this is the one irreversible,
        // minutes-long action in the app, and it used to be the only one with
        // no confirmation. The only way to export: the Finish tab that also did
        // it, with other size chips, is gone.
        Text(
            if (state.isExporting) "Exporting…" else "Export",
            style = MaterialTheme.typography.labelLarge,
            color = if (state.isExporting) SquishColors.TextMuted else SquishColors.Background,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (state.isExporting) SquishColors.Surface else SquishColors.Primary)
                .clickable(enabled = !state.isExporting && !state.isLoadingSource, onClick = onExport)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) SquishColors.TextPrimary else SquishColors.TextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** What is happening outside the edit itself: proxies, stills, a saved edit on offer, a failure. */
@Composable
private fun StatusCards(state: EditorUiState, viewModel: EditorViewModel, onStartNew: () -> Unit) {
    val proxies = state.proxiesInEdit.values.filter { it != ProxyStatus.NotNeeded }
    ProxyIndicator(
        status = state.proxyStatus,
        ready = proxies.count { it == ProxyStatus.Ready },
        total = proxies.size,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    PreparingIndicator(count = state.preparingStills, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

    // Only the inline offer lives here; the modal one is over everything.
    state.recovery?.takeIf { !it.modal }?.let { offer ->
        RecoveryBanner(
            offer = offer,
            onContinue = viewModel::acceptRecovery,
            onStartNew = onStartNew,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    if (state.setAsideNotice && state.recovery == null) {
        SetAsideNotice(
            onDismiss = viewModel::dismissSetAsideNotice,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    state.failure?.let { failure ->
        FailureCard(
            error = failure,
            onDismiss = viewModel::clearFailure,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Naming the project. Blank means no name, and the first clip's name shows
 * again - the placeholder says which.
 */
@Composable
private fun RenameDialog(current: String, placeholder: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)) {
        // In here, with the field: the dialog is its own window, composed after
        // the screen that asks for it, and focus asked for before the field is
        // there throws.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, SquishColors.Violet.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Name this project", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            OutlinedTextField(
                value = text,
                // A paste of a whole paragraph is cut back here rather than held
                // in the field - cut, not refused: a paste over the limit used to
                // do nothing at all. ProjectName decides the final length.
                onValueChange = { typed ->
                    val kept = ProjectName.cut(typed.text, ProjectName.FIELD_LENGTH)
                    text = if (kept.length == typed.text.length) typed
                    else TextFieldValue(kept, TextRange(kept.length))
                },
                singleLine = true,
                placeholder = { Text(placeholder, color = SquishColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SquishColors.Violet,
                    unfocusedBorderColor = SquishColors.Border,
                    focusedTextColor = SquishColors.TextPrimary,
                    unfocusedTextColor = SquishColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquishOutlinedButton(text = "Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                SquishPrimaryButton(text = "Save", modifier = Modifier.weight(1f), onClick = { onSave(text.text) })
            }
        }
    }
}

/** The most videos one pick adds. The picker needs a number; this is well past a normal batch. */
private const val MAX_PICK = 30

/** A tool's sheet takes this much of the editor's height, within the two limits below. */
private const val SHEET_SHARE = 0.42f

/** Room for a tool's heading and its first control or two, however short the screen. */
private val SHEET_MIN = 260.dp

/** On a tall screen, past this the sheet is only taking room from the picture. */
private val SHEET_MAX = 460.dp

/** With the keyboard up: the sheet's heading and a line or two of field. */
private val TYPING_SHEET_MIN = 160.dp

/** The most of the editor's height the strip takes, before it scrolls. */
private const val STRIP_SHARE = 0.4f

/**
 * The most the notices over the strip take - a failure, the saved-edit banner,
 * the proxy's progress - before they scroll. Their own share, apart from the
 * strip's, so no number of them can push the strip out of sight.
 */
private const val CARDS_SHARE = 0.18f

/** On its side, the sheet's share of the right-hand pane; the strip has the rest. */
private const val LANDSCAPE_SHEET_SHARE = 0.5f

/** The same with a sheet open, when the strip is its ruler and one row. */
private const val STRIP_SHARE_WITH_SHEET = 0.3f
