@file:OptIn(UnstableApi::class)

package com.squish.app.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.squish.app.editor.Timecode
import com.squish.app.ui.theme.SquishColors
import com.squish.app.ui.theme.tabularFigures
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/** One source in a preview, with the length the caller already probed. */
data class PreviewSource(val uri: Uri, val durationMs: Long, val label: String = "")

/**
 * A watchable preview of exactly what a tool is about to write.
 *
 * Every one-job tool used to render blind: trim had two numbers and no picture,
 * so "keep 0:04 to 0:11" meant nothing until the file came out the other end.
 * This is the fix - the same footage, the same in and out points, playing.
 *
 * It plays a list because merge is a list: ExoPlayer's own playlist runs them end
 * to end with no gap, which is precisely what a merge produces, so the preview and
 * the render agree by construction rather than by two pieces of code being kept in
 * step.
 *
 * [rangeStartMs] and [rangeEndMs] bound playback without re-cutting the media.
 * Clipping the MediaItem instead would be tidier, but the range moves under a
 * dragging thumb and every change would re-open and re-buffer the file - the
 * clamp below costs nothing and survives a drag.
 */
@Composable
fun ClipPreview(
    sources: List<PreviewSource>,
    accent: Color,
    modifier: Modifier = Modifier,
    rangeStartMs: Long = 0L,
    rangeEndMs: Long = 0L,
    aspect: Float = 16f / 9f,
    audioOnly: Boolean = false,
    /** Where to jump to; changing [seekNonce] is what makes the jump happen. */
    seekToMs: Long = 0L,
    seekNonce: Long = 0L
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    var positionMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    val totalMs = remember(sources) { sources.sumOf { it.durationMs }.coerceAtLeast(0L) }
    // Where each source begins on the joined timeline, so a position in the
    // playlist can be read as one number and scrubbed as one bar.
    val offsets = remember(sources) {
        val starts = ArrayList<Long>(sources.size)
        var cursor = 0L
        for (source in sources) {
            starts.add(cursor)
            cursor += source.durationMs
        }
        starts
    }

    val endMs = if (rangeEndMs > rangeStartMs) rangeEndMs else totalMs
    val startMs = rangeStartMs.coerceIn(0L, endMs)

    val playlistKey = remember(sources) { sources.joinToString("|") { it.uri.toString() } }
    LaunchedEffect(playlistKey) {
        player.setMediaItems(sources.map { MediaItem.fromUri(it.uri) })
        player.prepare()
        player.playWhenReady = false
    }

    DisposableEffect(player) { onDispose { player.release() } }

    // Read through state that is kept current, never captured directly.
    //
    // The ticker below is a `LaunchedEffect` keyed on the player, so it starts once
    // and runs for the life of the preview with whatever these were on the *first*
    // composition. On the first composition there is a source but no measured
    // duration yet - the probe is still running - so every one of them was zero,
    // and they stayed zero for the rest of the session. The effect of that was a
    // preview that would not play: the loop compared the position against an end
    // point of zero, decided the clip had finished, and paused it again within a
    // frame of every press of play. Reading through `rememberUpdatedState` means
    // the loop sees the real numbers the moment the probe returns them.
    val latestCount by rememberUpdatedState(sources.size)
    val latestOffsets by rememberUpdatedState(offsets)
    val latestTotal by rememberUpdatedState(totalMs)
    val latestStart by rememberUpdatedState(startMs)
    val latestEnd by rememberUpdatedState(endMs)

    fun jointPosition(): Long {
        val index = player.currentMediaItemIndex.coerceIn(0, (latestCount - 1).coerceAtLeast(0))
        val base = latestOffsets.getOrElse(index) { 0L }
        return (base + player.currentPosition).coerceIn(0L, latestTotal)
    }

    fun seekJoint(target: Long) {
        if (latestCount == 0) return
        val clamped = target.coerceIn(0L, latestTotal)
        val index = latestOffsets.indexOfLast { it <= clamped }.coerceAtLeast(0)
        player.seekTo(index, clamped - latestOffsets[index])
        positionMs = clamped
    }

    LaunchedEffect(seekNonce) { if (seekNonce > 0) seekJoint(seekToMs) }

    // Entering a new range parks the playhead at its head, so the first frame on
    // screen is the first frame that will be kept.
    LaunchedEffect(startMs) { if (!playing) seekJoint(startMs) }

    LaunchedEffect(player) {
        while (true) {
            val live = player.isPlaying
            if (live != playing) playing = live
            if (live) {
                val at = jointPosition()
                // The range is the edit. Running past its out point would preview
                // footage the render is about to drop. An end point of zero means
                // nothing has been measured yet, and is not a clip that has ended.
                if (latestEnd > 0L && at >= latestEnd) {
                    player.pause()
                    seekJoint(latestStart)
                } else {
                    positionMs = at
                }
            }
            delay(TICK)
        }
    }

    fun toggle() {
        if (player.isPlaying) {
            player.pause()
        } else {
            val at = jointPosition()
            // Rewind to the head only when the playhead is genuinely outside the
            // range. With an unmeasured clip that test used to be true of every
            // position, so play seeked to zero and the loop stopped it again.
            if (latestEnd > 0L && (at < latestStart || at >= latestEnd - 40L)) {
                seekJoint(latestStart)
            }
            player.play()
        }
        playing = player.isPlaying
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (audioOnly) 3.2f else aspect.coerceIn(0.4f, 2.5f))
                .clip(RoundedCornerShape(16.dp))
                .background(SquishColors.Background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { toggle() }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (audioOnly) {
                // No picture to show, so the surface says what it is rather than
                // being a black rectangle that looks broken.
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.55f),
                    modifier = Modifier.size(34.dp)
                )
            } else {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (!playing) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentSweep(accent)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = SquishColors.Background,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .clickable { toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            ScrubBar(
                positionMs = positionMs,
                startMs = startMs,
                endMs = endMs,
                totalMs = totalMs,
                accent = accent,
                modifier = Modifier.weight(1f),
                onScrub = { seekJoint(it) }
            )

            Text(
                Timecode.format((positionMs - startMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelSmall.tabularFigures(),
                color = SquishColors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * The scrub bar, drawn so the kept range is visible inside the whole file.
 *
 * The part outside the in and out points stays on screen, dimmed. Hiding it would
 * lose the one piece of context that makes a trim legible: how much is being
 * thrown away, and from which end.
 */
@Composable
private fun ScrubBar(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    totalMs: Long,
    accent: Color,
    modifier: Modifier = Modifier,
    onScrub: (Long) -> Unit
) {
    if (totalMs <= 0) {
        Box(modifier = modifier)
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(28f)
            .clip(RoundedCornerShape(999.dp))
            .pointerScrub(totalMs, onScrub)
    ) {
        val h = size.height
        val midY = h / 2f

        drawLine(
            color = SquishColors.Border,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = h * 0.45f,
            cap = StrokeCap.Round
        )

        val x0 = size.width * (startMs.toFloat() / totalMs)
        val x1 = size.width * (endMs.toFloat() / totalMs)
        drawLine(
            color = accent.copy(alpha = 0.45f),
            start = Offset(x0, midY),
            end = Offset(x1, midY),
            strokeWidth = h * 0.45f,
            cap = StrokeCap.Round
        )

        val px = size.width * (positionMs.toFloat() / totalMs)
        drawCircle(color = accent, radius = h * 0.42f, center = Offset(px, midY))
    }
}

/** Drag anywhere on the bar to scrub, rather than having to find a thumb. */
private fun Modifier.pointerScrub(totalMs: Long, onScrub: (Long) -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(totalMs) {
            detectHorizontalDragGestures { change, _ ->
                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                onScrub((fraction * totalMs).toLong())
            }
        }
    ).then(
        Modifier.pointerInput(totalMs) {
            detectTapGestures { offset ->
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                onScrub((fraction * totalMs).toLong())
            }
        }
    )

/** Sixteen a second. A scrub bar does not need a frame's worth of precision. */
private val TICK = 60.milliseconds
