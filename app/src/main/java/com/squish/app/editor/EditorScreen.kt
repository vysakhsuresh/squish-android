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
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.squish.app.data.ProjectRules
import com.squish.app.editor.edits.TextEdits
import com.squish.app.home.countOf
import com.squish.app.media.ExportStage
import com.squish.app.media.keepReadAccess
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineActionBar
import com.squish.app.timeline.TimelineEditor
import com.squish.app.timeline.stripRowsHeight
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.RenameDialog
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.CoachMark
import com.squish.app.settings.Preferences
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
    /** The project to open: its saved edit, or the files it was staged to start from. */
    projectId: String,
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
    var renaming by remember { mutableStateOf(false) }
    // A photo overlay waiting on "make it a long clip?" before it goes to the main track.
    var confirmLongStill by remember { mutableStateOf<String?>(null) }
    // A finger on the playhead, told to the preview so it can serve the drag from
    // sync samples and land exactly when the finger lifts.
    var timelineScrubbing by remember { mutableStateOf(false) }
    // A colour being picked off the picture, and who asked for it - the text
    // sheet's colour picker. While set, the picture takes one tap and answers.
    var eyedropper by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    // Given up with whatever asked for it: the sheet closing, the selection
    // changing. It stayed over the picture, its one tap recolouring a line no
    // longer selected, and the box could not be grabbed until its small Cancel
    // was found.
    LaunchedEffect(openTool, state.selectedClipId) { eyedropper = null }

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    val kind = state.selectionKind
    val canTransition = state.selectedCanTransition
    // Select more on, or several already selected: the toolbar is the set's.
    // From the press itself, so the mode is seen to be on before a second tap.
    val multi = state.multiSelected || (state.selectingMore && state.selectedClipId != null)
    // A photo, blank or freeze on a video track is a file with nothing moving
    // in it: the tools that need footage are not offered (ToolRules.toolsFor).
    val footage = state.videoClips.firstOrNull { it.id == state.selectedClipId }?.let { it.isFootage || it.isStillPicture } ?: true
    // A clip's own tool closes when the selection no longer has it - Speed with
    // a caption selected, anything once the selection is deleted or let go.
    LaunchedEffect(openTool, kind, canTransition, multi, footage) {
        val tool = openTool ?: return@LaunchedEffect
        if (!sheetSurvives(tool, kind, canTransition, multi, footage)) openToolName = null
    }
    // Replace: the file is picked first, and the sheet asking where in it to
    // start comes up when the pick has been read - or not at all for a photo,
    // which goes straight in. The sheet goes with the question.
    LaunchedEffect(state.replacing) {
        if (state.replacing != null) openToolName = Tool.Replace.name
        else if (openToolName == Tool.Replace.name) openToolName = null
    }
    // The file to go under the selected clip, for Replace.
    // The clip Replace was pressed on, kept across a recreation: the selection
    // is not, and after the app was killed behind the picker the pick arrived
    // with nothing selected and was dropped. The view model holds it until
    // the edit is open again (replaceWhenOpened).
    var replaceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = replaceTarget ?: viewModel.state.value.selectedClipId
        replaceTarget = null
        if (uri != null) {
            context.keepReadAccess(uri)
            target?.let { viewModel.replaceWhenOpened(it, uri) }
        }
    }
    // The file to go under every clip that plays one that can no longer be
    // opened (EditorViewModel.relink): from the picker, or, for a file the
    // picker never shows, from the file manager.
    val pickRelink = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            context.keepReadAccess(uri)
            viewModel.relinkWhenOpened(uri)
        }
    }
    val browseRelink = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.keepReadAccess(uri)
            viewModel.relinkWhenOpened(uri)
        }
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
            // A fitted file that missed its limit is already in the gallery;
            // back keeps it, as the card's first button does, and lands on
            // the done screen. It used to close the sheet over the question,
            // which came back the next time Export was opened - and "Try
            // again, tighter" then ran whatever the sheet had been set to.
            state.fitOvershoot != null -> viewModel.keepOversize { path ->
                exportSheetOpen = false
                onExported(path)
            }
            exportSheetOpen -> exportSheetOpen = false
            // The eyedropper is the innermost thing of all: back gives up the pick, not the sheet.
            eyedropper != null -> eyedropper = null
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
    // to sleep under a long one - nor, when Settings says so, under an edit:
    // reading a caption back against the picture takes longer than a screen
    // timeout, and a fading screen mid-scrub is the one thing CapCut never does.
    val view = LocalView.current
    val keepAwakeWhileEditing = remember { Preferences.editorDefaults(context).keepScreenOn }
    DisposableEffect(state.isExporting, keepAwakeWhileEditing) {
        view.keepScreenOn = state.isExporting || keepAwakeWhileEditing
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
    // building an edit from a handful of shots a chore. In at the playhead, where
    // the strip is looking - see insertSourcesAtPlayhead.
    val pickExtraClips = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.clips.insertSourcesAtPlayhead(uris)
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
    // Free stock footage: asked for online first, then a grid; the pick lands at the playhead.
    val stockGate = com.squish.app.online.rememberOnlineGate()
    var showStock by remember { mutableStateOf(false) }
    if (showStock) {
        com.squish.app.online.StockVideoSheet(
            onPicked = { uri -> showStock = false; viewModel.clips.insertSourcesAtPlayhead(listOf(uri)) },
            onDismiss = { showStock = false }
        )
    }
    val addOverlay = {
        pickOverlayClips.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }
    // A picture for the canvas's background, behind footage that does not
    // fill the frame's shape.
    val pickBackgroundImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.clips.setCanvasImage(it) }
    }
    val addText = {
        newLineId = viewModel.text.addCaptionAtPlayhead()
        openToolName = Tool.Edit.name
    }
    // A title the same way: dropped, and opened for typing with its sample
    // words selected, so the next thing typed is what it says.
    val addTitle: (TitlePreset) -> Unit = { preset ->
        newLineId = viewModel.text.addTitle(preset)
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
            Tool.ExtractAudio -> state.selectedClipId?.let(viewModel.audio::extractAudio)
            Tool.Speak -> state.selectedClipId?.let(viewModel.text::speak)
            Tool.Flip -> state.selectedClipId?.let(viewModel.text::flip)
            Tool.Rotate -> state.selectedClipId?.let(viewModel.clips::turnClip)
            Tool.Mirror -> state.selectedClipId?.let(viewModel.clips::mirrorClip)
            Tool.Freeze -> state.selectedClipId?.let(viewModel.clips::freezeFrame)
            Tool.Reverse -> state.selectedClipId?.let(viewModel.clips::reverseClip)
            Tool.Replace -> {
                replaceTarget = state.selectedClipId
                pickReplacement.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        .build()
                )
            }
            Tool.CopyAttributes -> state.selectedClipId?.let(viewModel.clips::copyAttributes)
            Tool.PasteAttributes -> state.selectedClipId?.let(viewModel.clips::pasteAttributes)
            // On with something selected; pressed again ("Done selecting"), the
            // adding stops and the set stays for Delete or a carry.
            Tool.SelectMore -> viewModel.setSelectingMore(!state.selectingMore)
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
        // The take being recorded is drawn, not in the edit: a tap on it selects nothing.
        if (id != RECORDING_CLIP_ID) {
            val before = state.selectedClipId
            // With Select more on, a tap adds to the set or takes from it.
            if (state.selectingMore) viewModel.toggleSelected(id) else viewModel.selectClip(id)
            if (id != null && id != before && openTool?.levelZero == true) openToolName = null
            // A line or sticker is edited on the picture, by its box: picked on the
            // strip away from the playhead it was on no frame shown, and its box,
            // its Edit and its Delete were nowhere. The playhead goes to it, past
            // its arrival, so it stands there whole.
            state.textOverlays.firstOrNull { it.id == id }?.let { line ->
                if (!state.selectingMore && state.playheadMs !in line.startMs until line.endMs) {
                    viewModel.seekTo((line.startMs + line.motionInMs).coerceAtMost(line.endMs - 1L).coerceAtLeast(line.startMs))
                }
            }
        }
    }
    // A clip taken hold of to be carried: selected without ever leaving the
    // set (ClipEdits.liftClip), so a selected clip carries the selection.
    val liftFromStrip: (String) -> Unit = { id ->
        if (id != RECORDING_CLIP_ID) {
            val before = state.selectedClipId
            viewModel.clips.liftClip(id)
            if (id != before && openTool?.levelZero == true) openToolName = null
        }
    }

    // The picture, its transport and everything drawn over it. Movable, so a
    // rotation that lays the screen out in two panes carries the same players
    // across instead of building new ones and loading every clip again.
    val preview = remember {
        movableContentOf { modifier: Modifier ->
            EditorPreview(
                state = state,
                viewModel = viewModel,
                // Read here too: the file first opened is known only once the
                // project has loaded, after this lambda was remembered.
                sourceUri = state.sourceUri ?: Uri.EMPTY,
                // Read here, not captured: this lambda is remembered once, and a
                // value worked out outside it would stay whatever it first was.
                openTool = openToolName?.let { name -> Tool.entries.firstOrNull { it.name == name } },
                fullscreen = fullscreen,
                timelineScrubbing = timelineScrubbing,
                onFullscreen = { fullscreen = it },
                onCloseSheet = { openToolName = null },
                onOpenTool = { tool -> openToolName = tool.name },
                eyedropper = eyedropper,
                onEyedropperDone = { eyedropper = null },
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
                val density = LocalDensity.current
                val imePx = WindowInsets.ime.getBottom(density)
                val typing = imePx > 0
                // The keyboard's height, from the last time it was up. The Edit
                // sheet's tabs fold the keyboard, and the sheet then takes the
                // keyboard's room so its tab row stays where the finger found it
                // - as CapCut holds its text panel. It used to change to the
                // sheet's own height with the strip unfolding above it, so the
                // tabs jumped on every tab tapped and fast taps landed on the strip.
                var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
                var editChip by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(imePx) { if (imePx > keyboardPx) keyboardPx = imePx }
                // Only on a tab that is not for typing. Dismissing the keyboard on the
                // Keyboard tab itself used to hold its room too: an empty block where
                // the keys had been, the strip gone, and Done moving under the thumb.
                val holding = openTool == Tool.Edit && editChip != 0 && !typing && keyboardPx > 0
                val keyboardDp = with(density) { keyboardPx.toDp() }
                val cardsScroll = rememberScrollState()
                val stripScroll = rememberScrollState()

                val header = @Composable {
                    EditorHeader(
                        state = state,
                        onBack = leave,
                        // Not while the draft is still loading: its name, landing, would
                        // write over the one typed.
                        onRename = if (state.isLoadingSource) null else ({ renaming = true }),
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        onExport = { exportSheetOpen = true }
                    )
                }
                // Beside the strip, never in its column: in there a failure or a
                // banner pushed the compressed strip out of its capped height, and
                // the strip is the one thing section 2 says never goes.
                val cards = @Composable {
                    StatusCards(
                        state = state,
                        viewModel = viewModel,
                        onRelink = {
                            pickRelink.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    .build()
                            )
                        },
                        onBrowseRelink = { types -> browseRelink.launch(types) }
                    )
                }
                val controls = @Composable { compactStrip: Boolean, rowsHeight: Dp ->
                    val timeline = state.toTimeline()
                    TimelineEditor(
                        state = timeline,
                        onSelect = selectFromStrip,
                        onLift = liftFromStrip,
                        // Rows never fold to thin bars. Folding the tracks the selection
                        // is not on made the strip look squeezed and jumpy every time a
                        // clip was tapped (seen on the phone); CapCut keeps every row as
                        // it is, and the strip scrolls when there are more than fit.
                        foldRows = false,
                        onTrimEdge = viewModel.clips::trimEdgeTo,
                        onTrimHeadIn = viewModel.clips::trimHeadInTo,
                        onTrimEnd = viewModel.clips::trimEnded,
                        onScrub = viewModel::scrubTo,
                        // The strip snaps a drag itself, and draws where it snapped to.
                        onSeek = viewModel::seekTo,
                        snapScrub = state.snapToMarkers,
                        rowsHeight = rowsHeight,
                        onTransitionTap = { clipId ->
                            viewModel.selectClip(clipId)
                            openToolName = Tool.Transition.name
                        },
                        markers = state.markers,
                        barMarkers = state.barGrid,
                        // The dots on the sounds, at the chosen density, and the
                        // grid the strip snaps to.
                        beats = state.beatGrid,
                        soundBeats = { clip -> AudioRules.chosenInWindow(clip, state.beats.every, state.beats.downbeatOffset) },
                        // The take being recorded, kept in view under the sheet.
                        focusClipId = RECORDING_CLIP_ID.takeIf { state.recording.phase == RecordingState.Phase.Recording },
                        fitNonce = state.fitNonce,
                        onZoomTo = viewModel::setPixelsPerSecond,
                        onReorder = viewModel.clips::reorderClip,
                        onPlace = viewModel.clips::placeClip,
                        onEffectMove = viewModel.clips::moveEffect,
                        onEffectTrimEdge = viewModel.clips::trimEffectTo,
                        onAddVideo = addVideos,
                        onAddBlank = viewModel.clips::insertBlankAtPlayhead,
                        onAddStock = { stockGate.request("Free stock footage") { showStock = true } },
                        onAddOverlay = addOverlay,
                        onCloseGap = viewModel.clips::closeGap,
                        onOpenSound = { openToolName = Tool.Sound.name },
                        // A line at once, as the video and overlay heads add at
                        // once (section 2's track heads); the Text sheet is on
                        // the toolbar. It opened the sheet, and a second "Add
                        // text" was needed there.
                        onOpenWords = addText,
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
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                val toolbar = @Composable {
                    val accent = kind.concept?.accent
                    ToolBar(
                        tools = toolsFor(kind, canTransition, multi, footage),
                        // Select more wears the primary colour while it is on, and
                        // says what the next press does: the one tool on the row
                        // that is a state, not an action.
                        accentOf = { tool ->
                            if (tool == Tool.SelectMore && state.selectingMore) SquishColors.Primary
                            else accent ?: tool.levelZeroAccent
                        },
                        labelOf = { tool -> if (tool == Tool.SelectMore && state.selectingMore) "Done selecting" else tool.label },
                        onTool = onTool,
                        enabled = { tool ->
                            when (tool) {
                                Tool.Clip -> state.videoClips.any { it.isMain }
                                Tool.Split -> state.canSplitHere
                                // Greyed while the voice is made, so a second tap
                                // has somewhere to go other than nowhere; and on a
                                // line with nothing to say.
                                Tool.Speak -> state.selectedClipId?.let { TextEdits.canSpeak(state, it) } == true
                                // Freeze wants the playhead on the clip; Reverse one
                                // render of it at a time; Paste something copied.
                                Tool.Freeze -> state.selectedClipId?.let { viewModel.clips.canFreeze(state, it) } == true
                                Tool.Reverse -> state.selectedClipId?.let { viewModel.clips.canReverse(state, it) } == true
                                Tool.PasteAttributes -> state.attributeClipboard != null
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
                            onAddTitle = addTitle,
                            newLineId = newLineId,
                            onSelectSound = { id ->
                                viewModel.selectClip(id)
                                openToolName = null
                            },
                            onEyedropper = { pick -> eyedropper = pick },
                            onEyedropperCancel = { eyedropper = null },
                            onChipChanged = { editChip = it },
                            onEditLine = { id ->
                                viewModel.selectClip(id)
                                openToolName = Tool.Edit.name
                            },
                            onPickBackgroundImage = {
                                pickBackgroundImage.launch(
                                    PickVisualMediaRequest.Builder()
                                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        .build()
                                )
                            },
                            modifier = modifier
                        )
                    }
                }

                when {
                    state.isLoadingSource -> Column(modifier = Modifier.fillMaxSize()) {
                        header()
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                CircularProgressIndicator(color = SquishColors.Primary)
                                // A new project's photos are rendered into clips
                                // before the editor opens, one after another; ten
                                // of them used to be a bare spinner for as long
                                // as that took, with no count.
                                if (state.preparingStills > 0) {
                                    Text(
                                        if (state.preparingStills == 1) "Preparing 1 photo…" else "Preparing ${state.preparingStills} photos…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SquishColors.Violet
                                    )
                                }
                            }
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
                            // Measured, since here it is whatever the notices and the
                            // toolbar leave, and the rows sized to it so the action bar
                            // under them fits.
                            BoxWithConstraints(
                                modifier = if (typing || holding) Modifier.heightIn(max = 0.dp) else Modifier.weight(1f)
                            ) {
                                val room = maxHeight
                                Column(modifier = Modifier.verticalScroll(stripScroll)) {
                                    controls(sheetOpen, stripRowsHeight(room))
                                }
                            }
                            if (sheetOpen) {
                                sheet(if (typing || holding) Modifier.weight(1f) else Modifier.height(available * LANDSCAPE_SHEET_SHARE))
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
                                        typing || holding -> 0.dp
                                        sheetOpen -> available * STRIP_SHARE_WITH_SHEET
                                        else -> available * STRIP_SHARE
                                    }
                                )
                                .verticalScroll(stripScroll)
                        ) {
                            // Rows sized to the share, so the action bar under them always fits.
                            controls(sheetOpen, stripRowsHeight(available * STRIP_SHARE))
                        }
                        if (sheetOpen) {
                            sheet(
                                Modifier.height(
                                    when {
                                        typing -> typingSheetHeightFor(available)
                                        // The keyboard's room and the height it had over the keyboard: the same top edge.
                                        holding -> typingSheetHeightFor(available - keyboardDp) + keyboardDp
                                        else -> sheetHeight
                                    }
                                )
                            )
                        } else toolbar()
                    }
                }

                if (exportSheetOpen) {
                    ExportSheet(
                        state = state,
                        viewModel = viewModel,
                        onDismiss = { exportSheetOpen = false },
                        // Closed before the done screen, so "Back to editor"
                        // lands on the bare editor: it used to land under the
                        // sheet, which had to be dismissed before editing on.
                        onExported = { path ->
                            exportSheetOpen = false
                            onExported(path)
                        }
                    )
                }
            }
        }
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
            // The title the header shows, not the file's: a gallery clip's label
            // is "1001323287.mp4", which the header itself never shows.
            placeholder = ProjectRules.displayTitle(state.videoClips.firstOrNull()?.label, state.startedAtMillis),
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
    /** A colour wanted off the picture, and who wants it; null when none is. */
    eyedropper: ((Int) -> Unit)?,
    onEyedropperDone: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (fullscreen) 0.dp else 14.dp))
            .background(SquishColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        // Where auto-reframe has the crop centred at the playhead, from the
        // shot under it, carried onto the canvas - for the outline drawn over
        // the picture. Shots are taken as the edit's own shape here; the
        // preview reads each surface's decoded shape, which only differs for
        // footage of another shape cut in.
        val reframeFocus = if (state.cropAspect.ratio != null) {
            val unrotated = if (state.sourceWidth > 0 && state.sourceHeight > 0) state.sourceWidth.toFloat() / state.sourceHeight else null
            FrameRules.reframeFocus(state.videoClips, state.playheadMs, state.rotationDegrees, state.canvasAspect) { unrotated }
        } else null
        // The overlay box on the picture. Its moves go through the one way
        // placement is written, so a drag is one undo step and an animated
        // overlay is keyed at the playhead; each gesture ends its step.
        val latestOpenTool by rememberUpdatedState(openTool)
        val latestCloseSheet by rememberUpdatedState(onCloseSheet)
        val latestOpenToolSheet by rememberUpdatedState(onOpenTool)
        // The painted size of each line of text, from the caption layer, for the
        // box round it.
        val textBoxes = remember { mutableStateMapOf<String, TextBox>() }
        val boxActions = remember(viewModel) {
            val textItem = { id: String -> viewModel.state.value.textOverlays.firstOrNull { it.id == id } }
            OverlayHandleActions(
                // As a tap on the strip: a layer picked while an add-things sheet
                // is up is a new job, and the sheet makes way for its tools.
                onSelect = { id ->
                    val current = viewModel.state.value
                    val before = current.selectedClipId
                    // With Select more on, a tap on a layer's box joins it to the
                    // set (never leaves it - that is the strip), as a tap on the
                    // strip would add it; it used to wipe the set to that one.
                    if (current.selectingMore) viewModel.joinSelection(id) else viewModel.selectClip(id)
                    if (id != before && latestOpenTool?.levelZero == true) latestCloseSheet()
                },
                onPlace = { id, t ->
                    viewModel.clips.setClipTransform(
                        id, scale = t.scale, offsetX = t.offsetXFraction, offsetY = t.offsetYFraction, rotation = t.rotationDegrees
                    )
                },
                onPlaceText = viewModel.text::placeText,
                onPlaceEnd = viewModel::endGesture,
                onDelete = { id ->
                    viewModel.selectClip(id)
                    viewModel.clips.deleteSelectedClip()
                },
                onDuplicate = { id ->
                    if (textItem(id) != null) viewModel.text.duplicateInPlace(id) else viewModel.layers.duplicateInPlace(id)
                },
                // A line's Edit is its keyboard, as CapCut's is; a sticker has no
                // words, so its numbers.
                onEdit = { id ->
                    viewModel.selectClip(id)
                    val item = textItem(id)
                    latestOpenToolSheet(if (item == null || item.sticker) Tool.Placement else Tool.Edit)
                },
                onOpen = { id ->
                    val item = textItem(id)
                    if (item != null) {
                        viewModel.selectClip(id)
                        latestOpenToolSheet(if (item.sticker) Tool.Placement else Tool.Edit)
                    }
                },
                // Only a line or a sticker has anything to open. On a clip
                // overlay the handler above returns without acting, so the
                // second of two quick taps did nothing - and stood in the way
                // of the tap that cycles down a stack of overlapping PiPs.
                canOpen = { id -> textItem(id) != null }
            )
        }
        // The hand-drawn crop is only taken hold of on the Frame sheet, where it
        // is the thing being worked on, and there it is modal: the overlay box
        // stands aside. Anywhere else it is drawn and left alone - it took every
        // touch on the picture whenever no overlay was selected, so an overlay
        // could not be tapped on the picture to select it.
        val editingCrop = state.cropAspect == CropAspect.Custom && openTool == Tool.Frame
        // A clip's picture being worked on - its crop window, its mask - is
        // shown plain with the tool over it; see TimelinePreview's pictureTool.
        val selectedPicture = state.videoClips.firstOrNull { it.id == state.selectedClipId }
        val pictureTool = when {
            selectedPicture == null -> null
            openTool == Tool.Crop -> PictureTool(
                selectedPicture.id, PictureTool.Kind.Crop,
                // Live while dragging, recorded once at the end: the view model
                // coalesces, so a gesture is one undo step.
                onCropChange = { viewModel.clips.setClipCropRect(selectedPicture.id, it) },
                onCropCommit = viewModel::endGesture
            )
            openTool == Tool.Mask && selectedPicture.mask != null -> PictureTool(
                selectedPicture.id, PictureTool.Kind.Mask,
                // The delta is this one event's, so each event has to start from
                // where the shape stands *now* - and on a keyed mask that is the
                // shape at the playhead, not the outer one. Read off the outer
                // mask, whose centre `withShapeAt` never writes once there are
                // keys, every event wrote frozen_base + one_event_delta: the
                // shape hopped a few pixels off centre on the first touch and
                // then sat there jittering for the whole drag.
                onMaskMove = move@{ dx, dy ->
                    val clip = viewModel.state.value.videoClips.firstOrNull { it.id == selectedPicture.id } ?: return@move
                    val held = clip.mask ?: return@move
                    val now = held.at(clip.sourceAt(viewModel.state.value.playheadMs))
                    viewModel.layers.updateMask(selectedPicture.id, centerX = now.centerXFraction + dx, centerY = now.centerYFraction + dy)
                },
                onMaskMoveEnd = viewModel::endGesture
            )
            else -> null
        }
        // Nor while a colour is being picked: that one tap is the eyedropper's.
        val overlayActions = boxActions.takeIf { !editingCrop && eyedropper == null && pictureTool == null }
        TimelinePreview(
            videoClips = state.videoClips,
            audioClips = state.audioClips,
            captions = state.textOverlays,
            effects = state.effects,
            fallbackUri = sourceUri,
            proxies = state.proxyUris,
            muteOriginal = state.muteOriginal,
            // Silent while a take is recorded, so the speaker stays out of the mic.
            muted = state.recording.active,
            // The green screen is not drawn while its colour is being picked
            // off the picture, so the loupe reads the screen and not the hole.
            keyPreview = !(eyedropper != null && openTool == Tool.Cutout),
            transportRequest = state.transportRequest,
            originalVolume = state.originalVolume,
            rotationDegrees = state.rotationDegrees,
            // The shape actually being kept, not the chosen ratio. A
            // hand-drawn crop has no ratio of its own, so passing the
            // enum's left the preview showing no crop at all until the
            // file came out the other end.
            cropRatio = state.previewCropRatio,
            // The rectangle itself, so captions sit where the export puts them.
            customCrop = state.cropRect.takeIf { state.cropAspect == CropAspect.Custom && !it.isFull },
            cropEditing = editingCrop,
            pictureTool = pictureTool,
            sourceAspect = state.sourceFrameAspect,
            canvasAspect = state.canvasAspect,
            canvasBackground = state.canvasBackground,
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
            textBoxes = textBoxes,
            modifier = Modifier.fillMaxSize(),
            // Inside the picture, so the crop rectangle is measured
            // against the frame rather than against the whole box.
            pictureOverlay = { kept ->
                // Live framing while cropping, so the ratio is never
                // chosen blind - and with the part being cropped away
                // still on screen, dimmed, which is the only way to see
                // what a crop is actually costing. Not in full screen,
                // which is for watching.
                // The crop's dim and its guides only on the Frame sheet, where
                // the crop is the thing being chosen; everywhere else the
                // preview is clipped to what the file keeps, and nothing is
                // drawn over it. They used to stay up in every tool once a
                // ratio was chosen.
                // The platform guide sits under whatever tool layer is up: it is
                // a thing to work against, not a thing to work on, so it never
                // takes a touch and never hides the crop's own handles.
                if (!fullscreen) {
                    // Inside the rectangle the file keeps, not over the whole
                    // canvas. The insets are fractions of the *delivered* frame -
                    // that is what a phone's own UI covers - and measured against
                    // the canvas they were fractions of something larger: with a
                    // 9:16 crop of a landscape edit the guide sat wide of the
                    // picture and promised room the file does not have. It was
                    // right while nothing was cropped, which is how it was seen
                    // to work on 4 October.
                    state.safeArea?.let { SafeAreaLayer(it, modifier = Modifier.inFrame(kept)) }
                }
                when {
                    fullscreen -> Unit
                    eyedropper != null -> EyedropperLayer(
                        onPick = { colour ->
                            colour?.let { eyedropper.invoke(it) }
                            onEyedropperDone()
                        },
                        // A screen's green is read over a wider patch than a
                        // title's colour: compressed cloth is noisy.
                        patch = if (openTool == Tool.Cutout) 9 else 5,
                        hint = if (openTool == Tool.Cutout) "Drag the loupe over the screen to key" else "Touch the picture to pick its colour",
                        modifier = Modifier.fillMaxSize()
                    )
                    editingCrop -> CustomCropOverlay(
                        rect = state.cropRect,
                        // Live while dragging, recorded once at the end:
                        // the view model coalesces, so a gesture is one
                        // undo step rather than one per frame of movement.
                        onChange = viewModel.clips::setCropRect,
                        // The open step closed when the finger lifts, as every
                        // other gesture does. It used to re-send the rect the
                        // state already held, which changes nothing and leaves
                        // the step open - so the next unrelated edit within the
                        // coalescing window joined this one, and one undo took
                        // both off.
                        onCommit = viewModel::endGesture,
                        modifier = Modifier.fillMaxSize()
                    )
                    openTool == Tool.Frame && !state.paddedCanvas ->
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
                    state.projectName ?: ProjectRules.displayTitle(state.videoClips.firstOrNull()?.label, state.startedAtMillis),
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (state.isExporting) SquishColors.Surface else SquishColors.Primary)
                .clickable(enabled = !state.isExporting && !state.isLoadingSource, role = Role.Button, onClick = onExport)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                if (state.isExporting) "Exporting…" else "Export",
                style = MaterialTheme.typography.labelLarge,
                color = if (state.isExporting) SquishColors.TextMuted else SquishColors.Background,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
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

/** What is happening outside the edit itself: proxies, stills, a file gone missing, a failure. */
@Composable
private fun StatusCards(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onRelink: () -> Unit,
    /** The file browser, on the kinds the missing file could be replaced by. */
    onBrowseRelink: (Array<String>) -> Unit
) {
    val context = LocalContext.current
    // Once, on the first edit ever opened: the three gestures the strip is
    // driven by, which nothing on screen says. Gone for good on "Got it".
    var coach by remember { mutableStateOf(!Preferences.coachSeen(context, Preferences.COACH_EDITOR)) }
    if (coach && !state.isLoadingSource) {
        CoachMark(
            text = "Tap a clip to edit it. Drag the strip to scrub, pinch to zoom, and long-press a clip to move it.",
            onDismiss = {
                Preferences.markCoachSeen(context, Preferences.COACH_EDITOR)
                coach = false
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
    val proxies = state.proxiesInEdit.values.filter { it != ProxyStatus.NotNeeded }
    ProxyIndicator(
        status = state.proxyStatus,
        ready = proxies.count { it == ProxyStatus.Ready },
        total = proxies.size,
        percent = state.proxyPercent,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    PreparingIndicator(count = state.preparingStills, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    SpeakingIndicator(speaking = state.speakingId != null, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    ReversingIndicator(
        reversing = state.reversing,
        nameOf = { id -> state.videoClips.firstOrNull { it.id == id }?.label ?: "clip" },
        onCancel = viewModel.clips::cancelReverse,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )

    state.failure?.let { failure ->
        FailureCard(
            error = failure,
            onDismiss = viewModel::clearFailure,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    // A file the edit plays that can no longer be opened: the clips stay on
    // the strip as placeholders, and another file can be put under all of
    // them at once. Under the failure that names it, and standing after the
    // failure is dismissed, until the file is relinked or the clips deleted.
    state.missingMedia?.let { uri ->
        val clip = (state.videoClips + state.audioClips).firstOrNull { it.uri == uri }
        RelinkCard(
            name = clip?.label?.takeIf { it.isNotBlank() },
            sound = clip?.kind == ClipKind.Audio,
            onRelink = onRelink,
            onBrowse = onBrowseRelink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * The missing file by name, and the ways to put another under its clips. A
 * sound has no place in the photo picker, so its one way in is the file
 * browser, held to audio; a picture or a video takes the picker (a photo
 * picked for a video lands as a still) with the browser beside it for what the
 * picker never shows.
 */
@Composable
private fun RelinkCard(name: String?, sound: Boolean, onRelink: () -> Unit, onBrowse: (Array<String>) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when {
                name != null -> "“$name” is missing"
                sound -> "A sound in this edit is missing"
                else -> "A file in this edit is missing"
            },
            style = MaterialTheme.typography.titleSmall,
            color = SquishColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Its clips are kept where they were. Pick the file again, or another one, and every clip that played it plays that instead.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sound) {
                SquishPrimaryButton(text = "Relink", onClick = { onBrowse(arrayOf("audio/*")) })
            } else {
                SquishPrimaryButton(text = "Relink", onClick = onRelink)
                SquishOutlinedButton(text = "Browse files", onClick = { onBrowse(arrayOf("video/*", "image/*")) })
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
