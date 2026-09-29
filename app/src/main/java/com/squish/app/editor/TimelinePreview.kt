@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.editor

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.squish.app.media.CanvasBackdrop
import com.squish.app.media.CaptionRenderer
import com.squish.app.media.StillClips
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.squish.app.ui.theme.SquishColors
import com.squish.app.ui.theme.tabularFigures
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * The preview surface: the composited edit, driven by [PreviewEngine].
 *
 * Two base surfaces run as A/B roll so a transition has both of its shots on
 * screen at once, and each overlay layer gets a surface above them. The engine
 * decides what each one shows and how it should be drawn; this only paints it.
 *
 * Framing happens here, on screen, not in the players: the rotation turns the
 * base views, the crop clips them, and captions are drawn over everything by
 * [CaptionLayer]. None of that ever touches a player's pipeline, which is what
 * makes Rotate 90 or a new crop instant instead of a reload that could wedge.
 *
 * Every surface is a TextureView. A SurfaceView is punched through the window and
 * composited by the system, so it ignores view alpha, transforms and clipping - on
 * one of those, every dissolve, slide and picture-in-picture here would silently
 * do nothing.
 */
@Composable
fun TimelinePreview(
    videoClips: List<Clip>,
    audioClips: List<Clip>,
    captions: List<TextOverlayItem>,
    effects: List<TimedEffect>,
    fallbackUri: Uri,
    /** Each heavy file's light stand-in, by the file. */
    proxies: Map<Uri, Uri>,
    muteOriginal: Boolean,
    originalVolume: Float,
    rotationDegrees: Int,
    cropRatio: Float?,
    /** Every level held at nothing: a voiceover is being taken over the picture. */
    muted: Boolean = false,
    /** Green screens drawn, or held off while a key colour is picked off the picture. */
    keyPreview: Boolean = true,
    /** The editor asking the transport to play or pause; see [TransportRequest]. */
    transportRequest: TransportRequest? = null,
    /** The shape of the picture after the rotation. */
    sourceAspect: Float,
    /**
     * The shape of the canvas everything is composed on: the picture's own, or
     * - with a background - the chosen ratio, the picture fitted whole inside
     * it over [canvasBackground] (see CanvasBackground). Overlay offsets are
     * fractions of this canvas.
     */
    canvasAspect: Float = sourceAspect,
    canvasBackground: CanvasBackground = CanvasBackground.NONE,
    playheadMs: Long,
    scrubNonce: Long,
    onPositionChange: (Long) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    /**
     * A frame back (-1) or forward (+1). Routed through the edit so the playhead
     * and picture move together, and worked out there, where the shot under the
     * playhead - whose frames these are - is known.
     */
    onStep: (Int) -> Unit = {},
    /** The picture has the whole screen; the transport grows a scrub bar, since the strip is gone. */
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    /** Where the full-screen scrub bar sends the playhead: exactly there, so the thumb and the picture agree. */
    onScrub: (Long) -> Unit = {},
    /**
     * A tap on the picture, before it is taken as play or pause: true when it was
     * used. A tap on empty picture lets go of the selection, and only with
     * nothing selected is the picture the play button.
     */
    onPictureTap: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    /**
     * A hand-drawn crop, when there is one. The picture is shown whole beneath it
     * (the crop tool dims what is cut away), but captions are laid out inside the
     * rectangle itself, where the export puts them - not in a centred box of the
     * same shape, which put a caption under the middle of the frame when the crop
     * was in a corner.
     */
    customCrop: CropRect? = null,
    /**
     * The hand-drawn crop is being worked on: the whole picture is shown under
     * it, dimmed by the crop tool. Otherwise the picture is clipped to the
     * rectangle, as it is to a ratio - what the file keeps, and nothing else.
     */
    cropEditing: Boolean = false,
    /**
     * A clip whose picture is being worked on - its crop window, its mask.
     * That clip is shown plain: unplaced, its crop window lifted, the frame's
     * crop lifted, so the whole of its picture is under the tool drawn over
     * it, the way CapCut's crop screen shows a clip.
     */
    pictureTool: PictureTool? = null,
    /** A finger is on the timeline. Optional: the engine recognises a scrub from its seeks anyway. */
    scrubbing: Boolean = false,
    /** What is selected, for the box on the picture around a selected overlay. */
    selectedClipId: String? = null,
    /**
     * What the overlays' box does - select, move, delete, copy. Null leaves the
     * overlays untouchable on the picture: full screen is for watching.
     */
    overlayActions: OverlayHandleActions? = null,
    /**
     * The painted size of each line of text, filled in by the caption layer and
     * read to put the box round the letters. Null leaves text without a box.
     */
    textBoxes: MutableMap<String, TextBox>? = null,
    /**
     * Drawn over the picture, inside its bounds.
     *
     * A slot rather than a sibling of the preview, because the picture no longer
     * fills its container: an overlay laid over the container would put its crop
     * rectangle partly on the surround beside the footage, and measure it against
     * the wrong width.
     */
    pictureOverlay: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val engine = remember { PreviewEngine(context) }
    val latestPosition by rememberUpdatedState(onPositionChange)
    val latestPlaying by rememberUpdatedState(onPlayingChange)

    var frame by remember { mutableStateOf(PreviewFrame()) }

    DisposableEffect(engine) {
        onDispose {
            engine.release()
            // The players are gone, so nothing is playing - said, or the editor
            // would go on believing the last state it was told.
            latestPlaying(false)
        }
    }

    // Every change, however small, reaches the engine: it reads positions,
    // opacity and placement from the clips it was last handed, so a change left
    // out of here is a change the preview does not show. Handing it over is
    // cheap - the engine itself decides what, if anything, needs a seek.
    // Captions are not the engine's any more (see CaptionLayer), and neither are
    // rotation and crop.
    LaunchedEffect(videoClips, audioClips, effects, fallbackUri, proxies, muteOriginal, originalVolume) {
        engine.setTimeline(
            videoClips, audioClips, effects, fallbackUri, proxies,
            muteOriginal, originalVolume
        )
    }
    LaunchedEffect(keyPreview) { engine.setKeyPreview(keyPreview) }

    // Photos on overlay rows, drawn here rather than by a player: a picture needs
    // no decoder, and keeps its transparency. Their shapes, once read, for the box.
    val stillAspects = remember { mutableStateMapOf<String, Float>() }
    // Where each line's box gesture began: its placement and the Transform its box had.
    val textStarts = remember { HashMap<String, Pair<TextPlacement, Transform>>() }

    LaunchedEffect(muted) { engine.setMuted(muted) }

    // The editor's ask of the transport - a recording starting the timeline, a
    // song auditioned stopping it - answered once each. The request standing
    // when this preview is first made was answered by the one before it (or
    // by nobody, and is stale); only a new one counts.
    var answeredRequest by remember { mutableStateOf(transportRequest) }
    LaunchedEffect(transportRequest) {
        val request = transportRequest ?: return@LaunchedEffect
        if (request == answeredRequest) return@LaunchedEffect
        answeredRequest = request
        if (request.play) engine.play() else engine.pause()
    }
    // The full-screen scrub bar is a finger on the timeline too.
    var barScrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(scrubbing, barScrubbing) {
        // A finger on the strip stops playback: the strip's middle is the
        // playhead, and playing on would pull the moment being looked for out
        // from under the finger. Paused first, so the scrub is served as one.
        if (scrubbing) engine.pause()
        engine.setScrubbing(scrubbing || barScrubbing)
    }

    // A deliberate jump - scrubbing the ruler, a nudge - as opposed to the playhead
    // simply advancing. Only the former should move the players.
    LaunchedEffect(scrubNonce) { engine.seekTo(playheadMs) }

    // Nothing plays behind the user's back. Home, the lock button, a call, the
    // photo picker: the transport stops, and it is where it was on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { engine.pause() }

    // Back in front, the paused frame is asked for again: the window may have
    // let go of the surfaces' buffers while it was hidden, and a paused player
    // draws nothing new on its own.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes) {
        if (resumes == 0) return@LaunchedEffect
        delay(RESUME_SETTLE)
        engine.redraw()
    }

    // A new picture size, once the view underneath has actually taken it. Asking
    // for the frame during the layout pass got it drawn at the old size. A
    // rotation lands here too: it changes the canvas's shape.
    var pictureSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pictureSize, rotationDegrees) {
        if (pictureSize == IntSize.Zero) return@LaunchedEffect
        delay(REDRAW_SETTLE)
        engine.redraw()
    }

    LaunchedEffect(engine) {
        while (true) {
            val next = engine.tick()
            if (next != frame) {
                if (next.positionMs != frame.positionMs) latestPosition(next.positionMs)
                if (next.isPlaying != frame.isPlaying) latestPlaying(next.isPlaying)
                frame = next
            }
            delay(TICK)
        }
    }

    val pictureAspect = if (sourceAspect > 0f) sourceAspect else 16f / 9f
    val canvas = if (canvasAspect > 0f) canvasAspect else pictureAspect
    val padded = canvas != pictureAspect

    // The transport sits under the picture rather than over it. Laid over the
    // bottom of the frame it hid whatever was there - a caption, a subtitle, the
    // bottom of a screen recording - which is exactly what an editor has to show.
    // The whole preview area, letterbox included: the overlay box is laid over
    // all of it, so a button hanging off the picture can still be pressed.
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    Column(modifier = modifier.background(Color.Black)) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .onSizeChanged { areaSize = it }
            // Nothing drawn outside the picture. A stabilised, zoomed or moved
            // frame is scaled up and shifted, and without this it spilled over
            // the transport below and covered the play button.
            .clipToBounds()
            .clickable(
                // The player's own controller is off, so the picture itself is the
                // play button - which is what people reach for anyway - unless
                // something is selected, when a tap on it is the way out.
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { if (!onPictureTap()) engine.togglePlay() }
            ),
        contentAlignment = Alignment.Center
    ) {
        // The moment the layers are drawn for - parked on the very end, the
        // last frame, as the engine does.
        val layerTime = PreviewRules.lastFrameTime(frame.positionMs, frame.durationMs)
        // Where auto-reframe has the crop centred: the subject in the shot under
        // the playhead, from that shot's own track.
        val focus = FrameRules.reframeFocus(videoClips, layerTime)
        // The part of the canvas the export keeps. A fixed ratio is clipped
        // here, which is pixel-for-pixel what the export writes; so is a
        // hand-drawn rectangle, except while it is being drawn, when the whole
        // picture stays visible under it and the crop tool dims the rest.
        val kept = if (customCrop != null) {
            PreviewBox.Frame(customCrop.left, customCrop.top, customCrop.right, customCrop.bottom)
        } else {
            PreviewBox.cropFrame(canvas, cropRatio, focus)
        }
        val clipped = if ((customCrop != null && cropEditing) || pictureTool != null) PreviewBox.Frame() else kept
        // Where the picture sits on a padded canvas: fitted whole, centred, over
        // the background - the place the export's Presentation puts it.
        val pictureFrame = if (padded) FrameRules.fittedFrame(canvas, pictureAspect) else PreviewBox.Frame()
        val shotUnder = videoClips.firstOrNull { it.isMain && layerTime >= it.timelineStartMs && layerTime < it.timelineEndMs }
            ?: videoClips.filter { it.isMain }.maxByOrNull { it.timelineEndMs }?.takeIf { it.timelineEndMs == layerTime }
        val stills = videoClips.filter {
            it.isOverlay && StillClips.isStill(it.uri) && layerTime >= it.timelineStartMs && layerTime < it.timelineEndMs
        }
        val overlayCovers = frame.overlays.any { it.covers } || stills.isNotEmpty()

        // The canvas the edit is composed on. Overlay offsets are fractions of it,
        // so they have to be measured against the picture rather than the letterbox.
        //
        // Fitted, not filled. `fillMaxSize` here handed the aspect ratio below a
        // set of constraints it could not change, so the ratio was ignored and the
        // picture took the whole container - which for a portrait clip in a
        // landscape container meant most of the frame was outside it. Matching the
        // height first and letting the width follow gives the largest rectangle of
        // the footage's own shape that fits, and nothing of the frame is lost.
        Box(
            modifier = Modifier
                .aspectRatio(ratio = canvas, matchHeightConstraintsFirst = true)
                .onSizeChanged { pictureSize = it }
        ) {
            // Everything the export composites, clipped to the frame it keeps -
            // layers included: the export cuts a picture-in-picture off at the
            // crop, so showing it whole over cropped-away footage was a promise the
            // file did not keep.
            Box(modifier = Modifier.fillMaxSize().clip(FrameShape(clipped))) {
                // Behind everything on a padded canvas: the colour, the picture,
                // or the blurred still of the shot under the playhead - the same
                // file the export lays under it.
                if (padded) {
                    Backdrop(
                        background = canvasBackground,
                        shot = shotUnder,
                        canvasAspect = canvas,
                        modifier = Modifier.fillMaxSize().zIndex(0f)
                    )
                }

                // The picture, fitted into the canvas when the canvas is padded
                // and the whole of it otherwise. A dip to black fades the shots
                // themselves, as the file does, so a padded canvas shows its
                // background through the dip rather than a black card over it.
                Box(modifier = Modifier.fillMaxSize().zIndex(1f).inFrame(pictureFrame)) {
                    VideoSurface(engine, engine.baseA, frame.surfaceA.plainFor(pictureTool), rotationDegrees, frame.blackVeil, pictureSize)
                    VideoSurface(engine, engine.baseB, frame.surfaceB.plainFor(pictureTool), rotationDegrees, frame.blackVeil, pictureSize)

                    // Empty space on the base track is a real part of the edit, and
                    // the exported file goes black here. Showing the last frame frozen
                    // instead would be a quiet lie about what you are about to render.
                    // Beneath the layers: a picture-in-picture running on past the base
                    // is still in the file, so it is still on screen.
                    if (frame.inGap) {
                        Box(modifier = Modifier.fillMaxSize().zIndex(6f).background(Color.Black)) {
                            if (!overlayCovers) {
                                Text(
                                    // After the last clip, with a song still going, the
                                    // picture has simply ended; between clips it is a hole.
                                    if (frame.pictureEnded) "End of picture" else "Gap — no clip here",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SquishColors.TextMuted,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // The layers, laid out in the frame the export keeps: that frame is
                // the export's canvas for an overlay (its Presentation fits the layer
                // to the cropped output, and its offsets are fractions of that).
                // Laid over the whole uncropped picture, a picture-in-picture placed
                // in the corner of a 9:16 crop sat outside the crop on screen.
                Box(modifier = Modifier.fillMaxSize().zIndex(10f).inFrame(kept)) {
                    frame.overlays.forEach { placement ->
                        // Keyed by layer. Without this, deleting layer 1 shifts layer 2 into
                        // its slot, and the TextureView already bound to layer 1's player
                        // gets reused for layer 2 - two layers driving one surface.
                        key(placement.layer) {
                            OverlaySurface(engine, engine.overlayPlayer(placement.layer), placement.plainFor(pictureTool))
                        }
                    }
                    stills.forEach { clip ->
                        key(clip.id) {
                            val plain = pictureTool?.clipId == clip.id
                            StillOverlay(
                                clip = if (plain) clip.copy(crop = clip.crop?.copy(rect = CropRect())) else clip,
                                transform = if (plain) Transform.Identity else clip.transformAt(layerTime),
                                onAspect = { aspect -> clip.uri?.let { stillAspects[it.toString()] = aspect } }
                            )
                        }
                    }
                }

                // The tool over the plain picture: the crop window, or the mask's
                // edge, laid over exactly the picture it works on - a shot's whole
                // frame inside the turn, an overlay's fitted picture.
                if (pictureTool != null) {
                    val clip = videoClips.firstOrNull { it.id == pictureTool.clipId }
                    if (clip != null && !clip.isOverlay) {
                        Box(modifier = Modifier.fillMaxSize().zIndex(31f).inFrame(pictureFrame)) {
                            Box(modifier = Modifier.turned(rotationDegrees)) {
                                val unrotated = if (PreviewBox.isQuarterTurn(rotationDegrees)) 1f / pictureAspect else pictureAspect
                                PictureToolLayer(pictureTool, clip, unrotated, clip.sourceAt(layerTime))
                            }
                        }
                    } else if (clip != null) {
                        val aspect = frame.overlays.firstOrNull { it.clipId == clip.id }?.aspect
                            ?: clip.uri?.let { stillAspects[it.toString()] }
                        Box(modifier = Modifier.fillMaxSize().zIndex(31f).inFrame(kept)) {
                            Box(modifier = Modifier.fitted(aspect)) {
                                PictureToolLayer(pictureTool, clip, aspect ?: 1f, clip.sourceAt(layerTime))
                            }
                        }
                    }
                }
            }

            CaptionLayer(
                captions = captions,
                // Parked on the very end, a caption that runs to it is still up, as it
                // is on the export's last frame.
                timeMs = PreviewRules.lastFrameTime(frame.positionMs, frame.durationMs),
                atRest = !frame.isPlaying,
                frame = kept,
                modifier = Modifier.fillMaxSize().zIndex(25f),
                boxes = textBoxes
            )

            // Above every layer: the crop rectangle and its handles are drawn over
            // a picture-in-picture, never under it.
            Box(modifier = Modifier.fillMaxSize().zIndex(30f)) {
                pictureOverlay()
            }
        }

        // Over the captions, so a layer under a title can still be taken hold
        // of, and over the whole preview rather than the kept frame: an overlay
        // may hang off the frame - over a crop's bars, the letterbox - and the
        // buttons drawn there were beyond reach, a tap on one letting go of the
        // overlay instead. The caller leaves it out while the hand-drawn crop is
        // being edited, which is modal.
        if (overlayActions != null && !fullscreen && pictureSize != IntSize.Zero && areaSize != IntSize.Zero) {
            // The box follows the edit, not the engine's last tick, so it stays
            // under the finger; the picture catches up a tick later.
            // The box is the cropped picture's shape: a clip's own crop changes
            // what is on screen, and the file fits the cropped picture.
            val onPicture = frame.overlays.mapNotNull { placement ->
                val clip = videoClips.firstOrNull { it.id == placement.clipId } ?: return@mapNotNull null
                OverlayOnPicture(
                    clip.id, clip.layer, clip.transformAt(layerTime), clip.placementAt(layerTime),
                    placement.aspect?.let { CropRules.croppedAspect(it, clip.crop) }
                )
            } + stills.map { clip ->
                OverlayOnPicture(
                    clip.id, clip.layer, clip.transformAt(layerTime), clip.placementAt(layerTime),
                    clip.uri?.let { stillAspects[it.toString()] }?.let { CropRules.croppedAspect(it, clip.crop) }
                )
            }
            val keptFrame = keptOnArea(kept, pictureSize, areaSize)
            // Text and stickers, above every picture layer as they are drawn:
            // the box round the letters the layer painted (TextGeometry), so
            // what is grabbed is what is on screen.
            val words = if (textBoxes == null) emptyList() else captions.mapIndexedNotNull { index, item ->
                if (CaptionRenderer.frameAt(item, layerTime) == null) return@mapIndexedNotNull null
                val box = textBoxes[item.id] ?: return@mapIndexedNotNull null
                val (x, y) = item.anchorAt(layerTime)
                val placed = TextGeometry.transformOf(x, y, item.rotationDegrees, box.width, box.height, keptFrame.width, keptFrame.height)
                val size = item.sizeSp
                OverlayOnPicture(
                    clipId = item.id,
                    layer = TEXT_LAYER_BASE + index,
                    drawn = placed,
                    placed = placed,
                    aspect = TextGeometry.aspect(box.width, box.height),
                    limit = { t -> TextGeometry.limited(t, placed, size) },
                    readout = { t, moving -> TextGeometry.readout(t, placed, size, moving) }
                )
            }
            // A line's gesture is handed on in the line's own terms. The box
            // gives the whole gesture from where it began, so the letters'
            // size is measured against the placement the gesture started from
            // - kept here from its first event, since by the next the line
            // has already changed under it.
            val actions = OverlayHandleActions(
                onSelect = overlayActions.onSelect,
                onPlace = { id, t ->
                    val item = captions.firstOrNull { it.id == id }
                    val word = words.firstOrNull { it.clipId == id }
                    if (item == null || word == null) overlayActions.onPlace(id, t)
                    else {
                        val (before, start) = textStarts.getOrPut(id) { item.placement to word.placed }
                        overlayActions.onPlaceText?.invoke(id, TextGeometry.placed(before, start, t))
                    }
                },
                onPlaceEnd = {
                    textStarts.clear()
                    overlayActions.onPlaceEnd()
                },
                onDelete = overlayActions.onDelete,
                onDuplicate = overlayActions.onDuplicate,
                onEdit = overlayActions.onEdit,
                onOpen = overlayActions.onOpen,
                onPlaceText = overlayActions.onPlaceText
            )
            OverlayHandles(
                overlays = onPicture + words,
                selectedId = selectedClipId,
                frame = keptFrame,
                showBox = !frame.isPlaying,
                actions = actions,
                onTouch = { engine.pause() },
                onEmptyTap = { if (!onPictureTap()) engine.togglePlay() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

        Transport(
            frame = frame,
            onToggle = { engine.togglePlay() },
            onStep = onStep,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onScrub = onScrub,
            onBarScrubbing = { barScrubbing = it }
        )
    }
}

/**
 * One base surface, drawn the way the engine asked for.
 *
 * @param veil how far a dip to black has taken the picture down: the shot's
 *   own alpha, as the file fades it, so what is behind shows through.
 * @param canvasSize the whole canvas, which a clip's own crop is fitted to.
 */
@Composable
private fun VideoSurface(
    engine: PreviewEngine,
    player: ExoPlayer,
    draw: SurfaceDraw,
    rotationDegrees: Int,
    veil: Float,
    canvasSize: IntSize
) {
    // The outer box is the canvas: the clip's placement and the transition act on
    // it, in the canvas's own units, after the rotation - which is the order the
    // export applies them in. The view inside is laid out unrotated and turned.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(draw.zIndex.toFloat())
            .graphicsLayer {
                alpha = if (draw.visible) (draw.alpha * (1f - veil)).coerceIn(0f, 1f) else 0f
                // The clip's own animated placement, plus whatever the transition
                // is doing to the whole surface.
                rotationZ = draw.transform.rotationDegrees
                scaleX = draw.transform.scale
                scaleY = draw.transform.scale
                translationX = draw.translateXFraction * size.width +
                    draw.transform.offsetXFraction * size.width / 2f
                translationY = draw.transform.offsetYFraction * size.height / 2f
            }
            .drawWithContent {
                if (draw.revealFraction >= 1f) {
                    drawContent()
                } else {
                    // A wipe: the incoming shot is revealed from the left edge.
                    clipRect(right = size.width * draw.revealFraction.coerceIn(0f, 1f)) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
    ) {
        // The clip's own crop sits on the unrotated picture, inside the turn,
        // and its window is fitted to the canvas as the export fits it - the
        // canvas measured in the picture's own orientation.
        Box(modifier = Modifier.turned(rotationDegrees)) {
            ClipCropped(
                crop = draw.crop,
                fit = { regionW, regionH ->
                    val (cw, ch) = PreviewBox.unrotatedSize(canvasSize.width.toFloat(), canvasSize.height.toFloat(), rotationDegrees)
                    CropRules.fitScale(regionW, regionH, cw, ch)
                }
            ) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).also {
                            // Left opaque: the base chain ends in the effects pass, which
                            // writes alpha 1, so there is no transparency here to show,
                            // and a non-opaque view read that straight-alpha output as
                            // premultiplied. Dissolves are view alpha, which an opaque
                            // TextureView honours.
                            engine.attachSurface(player, it)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * A clip whose picture is being worked on, and what the tool over it does.
 * See TimelinePreview's pictureTool.
 */
class PictureTool(
    val clipId: String,
    val kind: Kind,
    /** The crop window dragged: live, then committed when the finger lifts. */
    val onCropChange: (CropRect) -> Unit = {},
    val onCropCommit: () -> Unit = {},
    /** The mask's centre moved by the shader's fractions, then let go. */
    val onMaskMove: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    val onMaskMoveEnd: () -> Unit = {}
) {
    enum class Kind { Crop, Mask }
}

/** A shot drawn plain - unplaced, its crop window lifted - while its picture is worked on. */
private fun SurfaceDraw.plainFor(tool: PictureTool?): SurfaceDraw =
    if (tool == null || clipId != tool.clipId) this
    else copy(transform = Transform.Identity, crop = crop?.copy(rect = CropRect()))

private fun OverlayPlacement.plainFor(tool: PictureTool?): OverlayPlacement =
    if (tool == null || clipId != tool.clipId) this
    else copy(transform = Transform.Identity, crop = crop?.copy(rect = CropRect()))

/** The crop window or the mask's edge, over a clip's plain picture of [pictureAspect], at [sourceMs] of its file. */
@Composable
private fun PictureToolLayer(tool: PictureTool, clip: Clip, pictureAspect: Float, sourceMs: Long) {
    when (tool.kind) {
        PictureTool.Kind.Crop -> {
            val crop = clip.crop ?: ClipCrop()
            CustomCropOverlay(
                rect = crop.rect,
                onChange = tool.onCropChange,
                onCommit = tool.onCropCommit,
                ratio = crop.ratio.value,
                frameAspect = pictureAspect,
                modifier = Modifier.fillMaxSize()
            )
        }
        PictureTool.Kind.Mask -> {
            val mask = clip.mask ?: return
            // A tracked mask is drawn where the track has it at the frame showing.
            MaskOutlineLayer(
                mask = mask,
                centre = mask.centerAt(sourceMs),
                onMove = tool.onMaskMove,
                onMoveEnd = tool.onMaskMoveEnd,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A clip's own crop, on the view that shows it: the picture mirrored, turned
 * and zoomed under the window (the inner layer), the window cut out and
 * fitted to the canvas (the outer layer). Two layers because a layer clips in
 * its own space before it moves: the inner moves the picture under a fixed
 * window, the outer moves the window. The maths is CropRules' - the same the
 * export's shader inverts - so the two agree about what is kept.
 *
 * [fit] gives the scale that fits a region of the view, in the view's pixels,
 * to the canvas: the base's and an overlay's canvases are measured differently.
 */
@Composable
private fun ClipCropped(crop: ClipCrop?, fit: (regionW: Float, regionH: Float) -> Float, content: @Composable () -> Unit) {
    if (crop == null || crop.isIdentity) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
        return
    }
    val rect = crop.rect
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = FrameShape(PreviewBox.Frame(rect.left, rect.top, rect.right, rect.bottom))
                val s = fit(size.width * rect.width, size.height * rect.height)
                scaleX = s
                scaleY = s
                translationX = -((rect.left + rect.right) / 2f - 0.5f) * size.width * s
                translationY = -((rect.top + rect.bottom) / 2f - 0.5f) * size.height * s
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (crop.turnsPicture) {
                        rotationZ = crop.straightenDegrees
                        val z = CropRules.zoomToCover(crop.straightenDegrees, size.width / size.height.coerceAtLeast(1f))
                        scaleX = z * (if (crop.flipHorizontal) -1f else 1f)
                        scaleY = z * (if (crop.flipVertical) -1f else 1f)
                    }
                }
        ) {
            content()
        }
    }
}

/**
 * What a padded canvas shows round the picture: the colour, the picture
 * chosen, or a blurred still of the shot under the playhead - the very file
 * the export lays under it (CanvasBackdrop), so the two cannot differ.
 */
@Composable
private fun Backdrop(background: CanvasBackground, shot: Clip?, canvasAspect: Float, modifier: Modifier) {
    val context = LocalContext.current
    when (background.fill) {
        CanvasFill.Crop -> Unit
        CanvasFill.Colour -> Box(modifier = modifier.background(Color(background.colorArgb)))
        CanvasFill.Image, CanvasFill.Blur -> {
            val uri = if (background.fill == CanvasFill.Image) background.imageUri?.let(Uri::parse) else shot?.uri
            val atMs = shot?.let { CanvasBackdrop.stillMomentOf(it) } ?: 0L
            var still by remember(uri, atMs, canvasAspect, background.fill) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(uri, atMs, canvasAspect, background.fill) {
                if (uri == null) return@LaunchedEffect
                val file = withContext(Dispatchers.IO) {
                    if (background.fill == CanvasFill.Image) CanvasBackdrop.fromImage(context, uri, canvasAspect)
                    else CanvasBackdrop.blurred(context, uri, atMs, canvasAspect)
                }
                still = file?.let { StillPictures.load(context, Uri.fromFile(it)) }
            }
            val image = remember(still) { still?.asImageBitmap() }
            Box(modifier = modifier.background(Color(background.colorArgb))) {
                if (image != null) {
                    Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * A floating layer. The offsets are fractions of half the canvas, so ±1 puts the
 * layer's center on the edge - the same convention the export's placement matrix
 * uses, which is what keeps the two agreeing.
 *
 * The view is the layer's own shape, fitted into the canvas - the fit the export's
 * Presentation gives it - and not the whole canvas. A canvas-sized view had the
 * player letterbox the picture inside it, so a portrait reaction clip over a
 * landscape edit came with black bars down both sides of its own box.
 */
@Composable
private fun OverlaySurface(engine: PreviewEngine, player: ExoPlayer, placement: OverlayPlacement) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f + placement.layer)
            .onSizeChanged { canvasSize = it }
            .graphicsLayer {
                alpha = if (placement.visible) placement.opacity.coerceIn(0f, 1f) else 0f
                rotationZ = placement.transform.rotationDegrees
                scaleX = placement.transform.scale
                scaleY = placement.transform.scale
                // Measured against the canvas, not the layer's own box, so an
                // offset means the same distance whatever shape the layer is.
                translationX = placement.transform.offsetXFraction * size.width / 2f
                translationY = placement.transform.offsetYFraction * size.height / 2f
            }
    ) {
        // The clip's crop on the fitted picture, its window fitted to the canvas
        // in turn, as the export fits the cropped picture.
        Box(modifier = Modifier.fitted(placement.aspect)) {
            ClipCropped(
                crop = placement.crop,
                fit = { regionW, regionH -> CropRules.fitScale(regionW, regionH, canvasSize.width.toFloat(), canvasSize.height.toFloat()) }
            ) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).also {
                            // A keyed or masked layer has real transparency, and an opaque
                            // view would paint it black; so would any sliver the fit
                            // leaves before the decoder has reported its size. Its chain
                            // hands over premultiplied alpha, which is what a non-opaque
                            // view composites.
                            it.isOpaque = false
                            engine.attachSurface(player, it)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * A photo on an overlay row: the picture itself, placed exactly as a layer's
 * surface is (see [OverlaySurface]) - fitted into the canvas at its own shape,
 * then scaled, turned and moved in the canvas's units - with its transparency.
 */
@Composable
private fun StillOverlay(clip: Clip, transform: Transform, onAspect: (Float) -> Unit) {
    val uri = clip.uri ?: return
    val context = LocalContext.current
    var picture by remember(uri) { mutableStateOf(StillPictures.cached(uri)) }
    LaunchedEffect(uri) {
        val loaded = picture ?: StillPictures.load(context, uri)
        picture = loaded
        if (loaded != null && loaded.height > 0) onAspect(loaded.width.toFloat() / loaded.height)
    }
    val image = remember(picture) { picture?.asImageBitmap() } ?: return
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val aspect = if (image.height > 0) image.width.toFloat() / image.height else null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f + clip.layer)
            .onSizeChanged { canvasSize = it }
            .graphicsLayer {
                alpha = clip.opacity.coerceIn(0f, 1f)
                rotationZ = transform.rotationDegrees
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offsetXFraction * size.width / 2f
                translationY = transform.offsetYFraction * size.height / 2f
            }
    ) {
        // Cropped as a layer's surface is: the picture fitted, then its window fitted.
        Box(modifier = Modifier.fitted(aspect)) {
            ClipCropped(
                crop = clip.crop,
                fit = { regionW, regionH -> CropRules.fitScale(regionW, regionH, canvasSize.width.toFloat(), canvasSize.height.toFloat()) }
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Overlay pictures, decoded once and kept while they are in use. A handful at
 * most are on screen together; the cap is there so a long session with many
 * photos cannot grow without end.
 */
private object StillPictures {
    /** Long side of the copy drawn - past what a phone's preview box shows. */
    private const val MAX_SIDE = 1440
    private const val BUDGET_BYTES = 48 * 1024 * 1024

    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(BUDGET_BYTES) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    fun cached(uri: Uri): android.graphics.Bitmap? = cache.get(uri.toString())

    suspend fun load(context: android.content.Context, uri: Uri): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            cached(uri) ?: StillClips.previewBitmap(context, uri, MAX_SIDE)?.also { cache.put(uri.toString(), it) }
        }
}

/** Lays the content out over [frame] of the space it is given - a fraction of it, as the crop is. */
/**
 * The kept frame as a rectangle on the whole preview area: the canvas is
 * [picture] big, centred in [area], and the frame is where [inFrame] lays the
 * layers out inside it - rounded the same way, so the box sits on the layer to
 * the pixel.
 */
private fun keptOnArea(frame: PreviewBox.Frame, picture: IntSize, area: IntSize): Rect {
    val canvasLeft = (area.width - picture.width) / 2
    val canvasTop = (area.height - picture.height) / 2
    val left = canvasLeft + (frame.left * picture.width).roundToInt()
    val top = canvasTop + (frame.top * picture.height).roundToInt()
    val width = ((frame.right - frame.left) * picture.width).roundToInt().coerceIn(1, picture.width.coerceAtLeast(1))
    val height = ((frame.bottom - frame.top) * picture.height).roundToInt().coerceIn(1, picture.height.coerceAtLeast(1))
    return Rect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
}

private fun Modifier.inFrame(frame: PreviewBox.Frame): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val left = (frame.left * w).roundToInt()
    val top = (frame.top * h).roundToInt()
    val fw = ((frame.right - frame.left) * w).roundToInt().coerceIn(1, w.coerceAtLeast(1))
    val fh = ((frame.bottom - frame.top) * h).roundToInt().coerceIn(1, h.coerceAtLeast(1))
    val placeable = measurable.measure(Constraints.fixed(fw, fh))
    layout(w, h) { placeable.place(left, top) }
}

/**
 * Lays the content out unrotated - the canvas's sides swapped for a quarter turn -
 * centred, then turns it, so it covers the canvas exactly.
 */
private fun Modifier.turned(rotationDegrees: Int): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val (cw, ch) = PreviewBox.unrotatedSize(w.toFloat(), h.toFloat(), rotationDegrees)
    val placeable = measurable.measure(Constraints.fixed(cw.roundToInt(), ch.roundToInt()))
    layout(w, h) {
        placeable.placeWithLayer((w - placeable.width) / 2, (h - placeable.height) / 2) {
            rotationZ = PreviewBox.screenRotation(rotationDegrees)
        }
    }
}

/** The largest rectangle of [aspect] that fits, centred; all of it when the shape is not known yet. */
private fun Modifier.fitted(aspect: Float?): Modifier = layout { measurable, constraints ->
    if (aspect == null || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val (fw, fh) = PreviewBox.fittedSizeDp(aspect, w.toFloat(), h.toFloat())
    val placeable = measurable.measure(
        Constraints.fixed(fw.roundToInt().coerceAtLeast(1), fh.roundToInt().coerceAtLeast(1))
    )
    layout(w, h) { placeable.place((w - placeable.width) / 2, (h - placeable.height) / 2) }
}

@Composable
private fun Transport(
    frame: PreviewFrame,
    onToggle: () -> Unit,
    /** A frame back (-1) or forward (+1). */
    onStep: (Int) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    onScrub: (Long) -> Unit,
    onBarScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SquishColors.Background.copy(alpha = 0.72f))
    ) {
        // Full screen has no strip to scrub on, so the bar is the strip.
        if (fullscreen && frame.durationMs > 0L) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            // Leaving full screen mid-drag takes the bar away without its end;
            // the engine must not be left serving a scrub nobody is making.
            val latestBarScrubbing by rememberUpdatedState(onBarScrubbing)
            DisposableEffect(Unit) { onDispose { if (dragging != null) latestBarScrubbing(false) } }
            Slider(
                value = dragging ?: (frame.positionMs.toFloat() / frame.durationMs).coerceIn(0f, 1f),
                onValueChange = { v ->
                    if (dragging == null) onBarScrubbing(true)
                    dragging = v
                    onScrub((v * frame.durationMs).toLong())
                },
                onValueChangeFinished = {
                    dragging = null
                    onBarScrubbing(false)
                },
                colors = SliderDefaults.colors(
                    thumbColor = SquishColors.TextPrimary,
                    activeTrackColor = SquishColors.Primary,
                    inactiveTrackColor = SquishColors.Border
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // A frame at a time, the unit a cut is placed in. Five-second skips
            // were a player's controls, and the strip does long moves better.
            TransportButton(Icons.Filled.SkipPrevious, "Back one frame") { onStep(-1) }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SquishColors.Primary)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (frame.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (frame.isPlaying) "Pause" else "Play",
                    tint = SquishColors.Background,
                    modifier = Modifier.size(20.dp)
                )
            }
            TransportButton(Icons.Filled.SkipNext, "Forward one frame") { onStep(1) }

            Text(
                Timecode.format(frame.positionMs),
                style = MaterialTheme.typography.labelLarge.tabularFigures(),
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false
            )
            Text(
                "/ ${Timecode.format(frame.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            onToggleFullscreen?.let { toggle ->
                TransportButton(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    if (fullscreen) "Leave full screen" else "Full screen",
                    onClick = toggle
                )
            }
        }
    }
}

/** A frame step or the full-screen switch: a glyph in a thumb-sized target. */
@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = SquishColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}


/** A frame at 30fps: fast enough that the playhead does not visibly step. */
private val TICK = 33.milliseconds

/** Long enough for the view system to finish a resize the layout pass started. */
private val REDRAW_SETTLE = 150.milliseconds

/** Long enough for a window coming back to hand its surfaces over again. */
private val RESUME_SETTLE = 300.milliseconds

/** Text sits above every overlay row on the picture, so a tap where the two overlap takes the words. */
private const val TEXT_LAYER_BASE = 1_000

/** A [PreviewBox.Frame] of whatever it clips. */
private class FrameShape(private val frame: PreviewBox.Frame) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(
            Rect(
                frame.left * size.width,
                frame.top * size.height,
                frame.right * size.width,
                frame.bottom * size.height
            )
        )
}
