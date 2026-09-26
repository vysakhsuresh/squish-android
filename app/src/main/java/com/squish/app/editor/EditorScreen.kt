package com.squish.app.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.media.keepReadAccess
import com.squish.app.home.countOf
import com.squish.app.timeline.TimelineActionBar
import com.squish.app.timeline.TimelineEditor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors

/**
 * The editor's tools. Each carries a colour, and it is the same colour its track
 * wears on the timeline - so the rail is a legend for the strip above it rather
 * than nine identically grey words.
 */
enum class EditorTab(val label: String, val icon: ImageVector, val accent: Color) {
    /** In and out points. "Cut" is what the job is called everywhere but a menu. */
    Cut("Cut", Icons.Filled.ContentCut, SquishColors.Violet),

    /**
     * Crop and rotation together, which is framing rather than cropping - and
     * "Frame" was already the more accurate word for a panel that does both.
     */
    Frame("Frame", Icons.Filled.Crop, SquishColors.Violet),

    Speed("Speed", Icons.Filled.Speed, SquishColors.Blue),

    /** Transitions and the layers they happen between. */
    Blend("Blend", Icons.Filled.Layers, SquishColors.Magenta),

    Motion("Motion", Icons.Filled.Animation, SquishColors.Amber),
    Sound("Sound", Icons.Filled.GraphicEq, SquishColors.Cyan),
    Words("Words", Icons.Filled.ClosedCaption, SquishColors.Amber),
    Stickers("Stickers", Icons.Filled.EmojiEmotions, SquishColors.Magenta),
    Effects("Effects", Icons.Filled.Bolt, SquishColors.Violet),

    /** The look catalogue. It was called Effects and shows nothing but looks. */
    Looks("Looks", Icons.Filled.AutoAwesome, SquishColors.Magenta),

    Finish("Finish", Icons.Filled.FileUpload, SquishColors.Blue)
}

@Composable
fun EditorScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    /**
     * Which tool panel is open, or none.
     *
     * None, to begin with. The editor used to open with the trim panel already
     * down, which cost a third of the screen before anything had been asked for
     * and made the whole thing feel cramped from the first frame. What matters on
     * arrival is the picture, the strip, and the handful of actions under it;
     * a panel is something you ask for.
     */
    var tab by remember { mutableStateOf<EditorTab?>(null) }
    /**
     * Whether the open panel has taken the strip's room as well as its own.
     *
     * Panels that work on the picture or the file rather than on time - sizing
     * the export, choosing a look, framing the shot - open with the room already
     * taken, since the strip does nothing for them. The rest open beside the
     * strip, and either way the panel's own button flips it.
     */
    var panelExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { panelExpanded = tab in ROOMY_TABS }
    var exportSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.addAudioTrack(it)
        }
    }
    val pickExtraClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { context.keepReadAccess(it); viewModel.addVideoClip(it) }
    }
    val pickOverlayClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { context.keepReadAccess(it); viewModel.addOverlayClip(it) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackOrb(accent = tab?.accent ?: SquishColors.Violet, onClick = onBack, size = 40.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.videoClips.firstOrNull()?.label ?: "Your edit",
                        style = MaterialTheme.typography.titleMedium,
                        color = SquishColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${countOf(state.videoClips.size, "clip")} · ${Timecode.format(state.trimmedDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (state.isExporting) SquishColors.Surface else SquishColors.Primary)
                        // Opens the sheet rather than firing: this is the one
                        // irreversible, minutes-long action in the app, and it used
                        // to be the only one with no confirmation.
                        .clickable(enabled = !state.isExporting && !state.isLoadingSource) {
                            exportSheetOpen = true
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (state.isExporting) "Exporting…" else "Export",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.isExporting) SquishColors.TextMuted else SquishColors.Background
                    )
                }
            }

            if (state.isLoadingSource) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SquishColors.Primary)
                }
                return@Column
            }

            // The preview takes its height from the footage, inside limits. A
            // portrait clip in a fixed landscape box was showing its middle third
            // and hiding the rest; see PreviewBox for what the limits are for.
            //
            // With no tool open there is no panel to make room for, so the picture
            // takes that room instead of leaving an empty gap under the strip.
            val panelOpen = tab != null
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .then(if (panelOpen) Modifier else Modifier.weight(1f)),
                contentAlignment = Alignment.Center
            ) {
                val frameAspect = state.sourceFrameAspect
                val boxHeight = if (panelOpen) {
                    PreviewBox.heightDp(frameAspect, maxWidth.value).dp
                } else {
                    // As tall as the footage wants at full width, within the room
                    // there is - a landscape clip does not need the whole column.
                    val shape = frameAspect.takeIf { it > 0f && it.isFinite() } ?: PreviewBox.DEFAULT_ASPECT
                    (maxWidth.value / shape).coerceAtLeast(PreviewBox.MIN_HEIGHT_DP).dp.coerceAtMost(maxHeight)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(boxHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SquishColors.Surface),
                    contentAlignment = Alignment.Center
                ) {
                TimelinePreview(
                    videoClips = state.videoClips,
                    audioClips = state.audioClips,
                    captions = state.textOverlays,
                    effects = state.effects,
                    fallbackUri = sourceUri,
                    proxyUri = state.proxyUri,
                    muteOriginal = state.muteOriginal,
                    originalVolume = state.originalVolume,
                    grade = state.grade,
                    rotationDegrees = state.rotationDegrees,
                    cropRatio = state.cropAspect.ratio,
                    sourceAspect = state.sourceFrameAspect,
                    playheadMs = state.playheadMs,
                    scrubNonce = state.scrubNonce,
                    onPositionChange = viewModel::setPlayhead,
                    onPlayingChange = viewModel::setPlaying,
                    onJump = viewModel::jumpBy,
                    modifier = Modifier.fillMaxSize(),
                    // Inside the picture, so the crop rectangle is measured
                    // against the frame rather than against the whole box.
                    pictureOverlay = {
                        // Live framing while cropping, so the ratio is never
                        // chosen blind - and with the part being cropped away
                        // still on screen, dimmed, which is the only way to see
                        // what a crop is actually costing.
                        when {
                            state.cropAspect == CropAspect.Custom -> CustomCropOverlay(
                                rect = state.cropRect,
                                // Live while dragging, recorded once at the end:
                                // the view model coalesces, so a gesture is one
                                // undo step rather than one per frame of movement.
                                onChange = viewModel::setCropRect,
                                onCommit = { viewModel.setCropRect(state.cropRect) },
                                modifier = Modifier.fillMaxSize()
                            )
                            tab == EditorTab.Frame || state.cropAspect != CropAspect.Original ->
                                CropOverlay(
                                    aspect = state.cropAspect,
                                    modifier = Modifier.fillMaxSize()
                                )
                        }
                    }
                )
                }
            }

            ProxyIndicator(status = state.proxyStatus, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

            Spacer(modifier = Modifier.height(2.dp))

            val timeline = state.toTimeline()
            // Folded away while a panel has been given the room. The picture stays:
            // it is what the panel is changing.
            val showStrip = !(panelOpen && panelExpanded)
            if (showStrip) {
            TimelineEditor(
                state = timeline,
                onSelect = viewModel::selectClip,
                onMove = viewModel::moveClip,
                onTrim = viewModel::trimClip,
                onScrub = viewModel::scrubTo,
                onTransitionTap = { clipId ->
                    viewModel.selectClip(clipId)
                    tab = EditorTab.Blend
                },
                markers = state.markers,
                barMarkers = state.beats.every(4),
                isPlaying = state.isPlaying,
                fitNonce = state.fitNonce,
                onZoomTo = viewModel::setPixelsPerSecond
            )

            TimelineActionBar(
                state = timeline,
                onSplit = viewModel::splitAtPlayhead,
                onDelete = viewModel::deleteSelectedClip,
                onCloseGaps = viewModel::closeGaps,
                onZoomIn = viewModel::zoomIn,
                onZoomOut = viewModel::zoomOut,
                onFit = viewModel::fitTimeline,
                onGoToStart = viewModel::scrubToStart,
                onGoToEnd = viewModel::scrubToEnd,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                undoLabel = state.undoLabel,
                redoLabel = state.redoLabel,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            }

            state.recovery?.let { offer ->
                RecoveryBanner(
                    offer = offer,
                    onRestore = viewModel::acceptRecovery,
                    onDiscard = viewModel::dismissRecovery,
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

            // Nothing open: no panel at all, and the picture above has the room.
            tab?.let { openTab ->
            PanelBar(
                tab = openTab,
                expanded = panelExpanded,
                onToggleExpanded = { panelExpanded = !panelExpanded },
                onClose = { tab = null }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (openTab) {
                    EditorTab.Cut -> PrecisionTrimPanel(state, viewModel)
                    EditorTab.Frame -> CropPanel(state, viewModel)
                    EditorTab.Speed -> SpeedPanel(state, viewModel)
                    EditorTab.Blend -> TransitionPanel(
                        state = state,
                        viewModel = viewModel,
                        onAddOverlay = {
                            pickOverlayClip.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        }
                    )
                    EditorTab.Motion -> MotionPanel(state, viewModel)
                    EditorTab.Sound -> AudioPanel(
                        state = state,
                        viewModel = viewModel,
                        onPickAudio = { pickAudioTrack.launch(arrayOf("audio/*", "video/*")) }
                    )
                    EditorTab.Words -> CaptionsPanel(state, viewModel)
                    EditorTab.Stickers -> StickersPanel(state, viewModel)
                    EditorTab.Effects -> FxPanel(state, viewModel)
                    EditorTab.Looks -> EffectsPanel(state, viewModel)
                    EditorTab.Finish -> ExportPanel(
                        state = state,
                        viewModel = viewModel,
                        onAddClip = {
                            pickExtraClip.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            }

            // Tapping the open tool closes it, as does the panel's own close.
            ToolRail(selected = tab, onSelect = { tab = if (tab == it) null else it })
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

/**
 * The open tool's name, and two controls: more room, and close.
 *
 * A panel shares the screen with the picture, the strip and the rail, which
 * leaves it a slot - a setting chosen at the top and its result at the bottom
 * meant scrolling between them. "More room" folds the strip away while the panel
 * is open, and the picture stays, since it is what the panel is changing.
 */
@Composable
private fun PanelBar(
    tab: EditorTab,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(tab.icon, contentDescription = null, tint = tab.accent, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.titleSmall,
            color = SquishColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(tab.accent.copy(alpha = 0.12f))
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = tab.accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                if (expanded) "Show timeline" else "More room",
                style = MaterialTheme.typography.labelMedium,
                color = tab.accent
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close ${tab.label}",
                tint = SquishColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ToolRail(selected: EditorTab?, onSelect: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SquishColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        EditorTab.entries.forEach { entry ->
            val isSelected = entry == selected
            // A sprung nudge rather than a colour swap: at a glance down a row of
            // nine, movement is what tells you which one you just pressed.
            val scale by animateFloatAsState(if (isSelected) 1.06f else 1f, spring(), label = "tabScale")
            val tint by animateColorAsState(
                if (isSelected) SquishColors.Background else SquishColors.TextSecondary,
                label = "tabTint"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    // One width for all nine. Sized by their labels, "Mix" came out
                    // half the width of "Captions" and the row read as a ransom note.
                    .width(RAIL_ITEM_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isSelected) Modifier.background(accentSweep(entry.accent))
                        else Modifier.background(SquishColors.Background)
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    entry.icon,
                    contentDescription = entry.label,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Wide enough for "Motion", which is the longest label left in the rail.
 *
 * It used to be sized for "Captions". Shorter names are not only nicer to read -
 * they buy back the width, so a thumb sees more of the row before scrolling.
 */
private val RAIL_ITEM_WIDTH = 66.dp

/**
 * Tools that open with the timeline folded away. Every panel but Blend: on a
 * phone the strip and a panel together left the panel one row - Cut showed its
 * in point and not its out, Motion and Sound showed a paragraph and no button.
 * These work on the playhead and the picture, not on dragging clips, so the
 * strip costs them room and gives nothing; "Show timeline" is one tap away.
 * Blend keeps the strip because it acts on whichever clip is tapped there.
 */
private val ROOMY_TABS = EditorTab.entries.toSet() - EditorTab.Blend
