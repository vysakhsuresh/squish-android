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
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.squish.app.media.effects.Grade
import com.squish.app.timeline.Clip
import com.squish.app.ui.theme.SquishColors
import com.squish.app.ui.theme.tabularFigures
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * The preview surface: the composited edit, driven by [PreviewEngine].
 *
 * Two base surfaces run as A/B roll so a transition has both of its shots on
 * screen at once, and each overlay layer gets a surface above them. The engine
 * decides what each one shows and how it should be drawn; this only paints it.
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
    fallbackUri: Uri,
    proxyUri: Uri?,
    muteOriginal: Boolean,
    originalVolume: Float,
    grade: Grade,
    rotationDegrees: Int,
    cropRatio: Float?,
    sourceAspect: Float,
    playheadMs: Long,
    scrubNonce: Long,
    onPositionChange: (Long) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    /** Rewind (negative) or forward by a step. Routed through the edit so the playhead and picture move together. */
    onJump: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
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

    DisposableEffect(engine) { onDispose { engine.release() } }

    // Positions and trims are read fresh every tick, so this only has to run when
    // the set of clips itself changes shape.
    val editSignature = remember(
        videoClips, audioClips, captions, proxyUri, muteOriginal, originalVolume,
        grade, rotationDegrees, cropRatio
    ) {
        videoClips.joinToString("|") {
            "${it.id}@${it.timelineStartMs}:${it.sourceInMs}-${it.sourceOutMs}" +
                ":L${it.layer}:${it.opacity}:${it.staticTransform}" +
                ":${it.transitionIn.type}/${it.transitionIn.durationMs}" +
                ":K${it.keyframes}" +
                // Keying, masking and stabilisation are things the engine already
                // applies and this signature did not mention, so changing one of
                // them did not reach the preview: you set a green-screen colour,
                // nothing happened, and it only appeared once some unrelated edit
                // happened to change the signature. Anything the picture depends
                // on belongs here, or the preview is not a preview.
                ":C${it.chromaKey}" +
                ":M${it.mask}" +
                ":S${it.stabilizer.size}"
        } +
            "//" + audioClips.joinToString("|") { "${it.id}@${it.timelineStartMs}:${it.sourceInMs}-${it.sourceOutMs}:${it.volume}" } +
            "//" + proxyUri + muteOriginal + originalVolume + grade + captions +
            rotationDegrees + cropRatio +
            // Speed is a clip property now, so a ramp edit has to reach the engine
            // through the same signature every other clip edit does.
            videoClips.joinToString("|") { it.speedRamp.toString() } +
            audioClips.joinToString("|") { it.speedRamp.toString() }
    }

    LaunchedEffect(editSignature, fallbackUri) {
        engine.setTimeline(
            videoClips, audioClips, captions, fallbackUri, proxyUri,
            muteOriginal, originalVolume, grade, rotationDegrees, cropRatio
        )
    }

    // A deliberate jump - scrubbing the ruler, a nudge - as opposed to the playhead
    // simply advancing. Only the former should move the players.
    LaunchedEffect(scrubNonce) { engine.seekTo(playheadMs) }

    // A new picture size, once the view underneath has actually taken it. Asking
    // for the frame during the layout pass got it drawn at the old size.
    var pictureSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pictureSize) {
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

    // The transport sits under the picture rather than over it. Laid over the
    // bottom of the frame it hid whatever was there - a caption, a subtitle, the
    // bottom of a screen recording - which is exactly what an editor has to show.
    Column(modifier = modifier.background(Color.Black)) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(
                // The player's own controller is off, so the picture itself is the
                // play button - which is what people reach for anyway.
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { engine.togglePlay() }
            ),
        contentAlignment = Alignment.Center
    ) {
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
                .aspectRatio(
                    ratio = if (sourceAspect > 0f) sourceAspect else 16f / 9f,
                    matchHeightConstraintsFirst = true
                )
                .onSizeChanged { pictureSize = it }
        ) {
            VideoSurface(engine.baseA, frame.surfaceA)
            VideoSurface(engine.baseB, frame.surfaceB)

            if (frame.blackVeil > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(5f)
                        .background(Color.Black.copy(alpha = frame.blackVeil.coerceIn(0f, 1f)))
                )
            }

            pictureOverlay()

            frame.overlays.forEach { placement ->
                // Keyed by layer. Without this, deleting layer 1 shifts layer 2 into
                // its slot, and the TextureView already bound to layer 1's player
                // gets reused for layer 2 - two layers driving one surface.
                key(placement.layer) {
                    OverlaySurface(engine.overlayPlayer(placement.layer), placement)
                }
            }
        }

        // Empty space on the timeline is a real part of the edit, and the exported
        // file goes black here. Showing the last frame frozen instead would be a
        // quiet lie about what you are about to render.
        if (frame.inGap) {
            Box(modifier = Modifier.fillMaxSize().zIndex(20f).background(Color.Black)) {
                Text(
                    "Gap — no clip here",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

        Transport(frame = frame, onToggle = { engine.togglePlay() }, onJump = onJump)
    }
}

/** One base surface, drawn the way the engine asked for. */
@Composable
private fun VideoSurface(player: ExoPlayer, draw: SurfaceDraw) {
    AndroidView(
        factory = { context -> TextureView(context).also { player.setVideoTextureView(it) } },
        modifier = Modifier
            .fillMaxSize()
            .zIndex(draw.zIndex.toFloat())
            .graphicsLayer {
                alpha = if (draw.visible) draw.alpha.coerceIn(0f, 1f) else 0f
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
    )
}

/**
 * A floating layer. The offsets are fractions of half the canvas, so ±1 puts the
 * layer's center on the edge - the same convention the export's placement matrix
 * uses, which is what keeps the two agreeing.
 */
@Composable
private fun OverlaySurface(player: ExoPlayer, placement: OverlayPlacement) {
    AndroidView(
        factory = { context -> TextureView(context).also { player.setVideoTextureView(it) } },
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f + placement.layer)
            .graphicsLayer {
                alpha = if (placement.visible) placement.opacity.coerceIn(0f, 1f) else 0f
                rotationZ = placement.transform.rotationDegrees
                scaleX = placement.transform.scale
                scaleY = placement.transform.scale
                translationX = placement.transform.offsetXFraction * size.width / 2f
                translationY = placement.transform.offsetYFraction * size.height / 2f
            }
    )
}

@Composable
private fun Transport(
    frame: PreviewFrame,
    onToggle: () -> Unit,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SquishColors.Background.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SkipButton(Icons.Filled.Replay5, "Back 5 seconds") { onJump(-SKIP_MS) }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(SquishColors.Primary)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (frame.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (frame.isPlaying) "Pause" else "Play",
                tint = SquishColors.Background,
                modifier = Modifier.size(17.dp)
            )
        }
        SkipButton(Icons.Filled.Forward5, "Forward 5 seconds") { onJump(SKIP_MS) }

        Text(
            Timecode.format(frame.positionMs),
            style = MaterialTheme.typography.labelLarge.tabularFigures(),
            color = SquishColors.TextPrimary,
            maxLines = 1
        )
        Text(
            "/ ${Timecode.format(frame.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
    }
}

/** Rewind or forward, flanking play the way every player lays them out. */
@Composable
private fun SkipButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = SquishColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/** How far one press of rewind or forward goes. */
private const val SKIP_MS = 5_000L

/** A frame at 30fps: fast enough that the playhead does not visibly step. */
private val TICK = 33.milliseconds

/** Long enough for the view system to finish a resize the layout pass started. */
private val REDRAW_SETTLE = 150.milliseconds
