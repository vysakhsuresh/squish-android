package com.squish.app.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.squish.app.media.audio.Waveform
import com.squish.app.ui.components.WaveformCanvas
import com.squish.app.ui.theme.SquishColors

private val LANE_HEIGHT = 62.dp
private val HANDLE_WIDTH = 18.dp

/**
 * Stacked video and audio lanes on one shared time ruler.
 *
 * The video lane carries the filmstrip and the trim handles; the audio lane carries
 * the separate track's waveform and can be dragged bodily left or right to slide it
 * against the picture. Both lanes share the playhead and marker ticks, which is what
 * makes lining up a clap or a downbeat a visual act rather than a guessing game.
 */
@Composable
fun PrecisionTimeline(
    state: EditorUiState,
    thumbnails: List<ImageBitmap>,
    onTrimChange: (Long, Long) -> Unit,
    onAudioOffsetChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val duration = state.durationMs.coerceAtLeast(1)

    val latestTrim by rememberUpdatedState(onTrimChange)
    val latestOffset by rememberUpdatedState(onAudioOffsetChange)

    val startFraction = (state.trimStartMs.toFloat() / duration).coerceIn(0f, 1f)
    val endFraction = (state.trimEndMs.toFloat() / duration).coerceIn(0f, 1f)
    val playheadFraction = (state.playheadMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        modifier = modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.coerceAtLeast(1) },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LaneLabel("Video", SquishColors.Coral)

        Box(modifier = Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SquishColors.Surface)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    thumbnails.forEach { frame ->
                        Image(
                            bitmap = frame,
                            contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                WaveformCanvas(
                    waveform = state.videoWaveform,
                    color = SquishColors.TextPrimary.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth().height(20.dp).align(Alignment.BottomCenter)
                )

                DarkenOverlay(startFraction, alignEnd = false)
                DarkenOverlay(1f - endFraction, alignEnd = true)
                MarkerTicks(state.markers, duration)
                Playhead(playheadFraction, widthPx)
            }

            TrimHandle(
                fraction = startFraction,
                widthPx = widthPx,
                onDrag = { deltaPx ->
                    val deltaMs = (deltaPx / widthPx * duration).toLong()
                    val minGap = state.frameMs * 2
                    val next = (state.trimStartMs + deltaMs).coerceIn(0L, state.trimEndMs - minGap)
                    latestTrim(next, state.trimEndMs)
                }
            )
            TrimHandle(
                fraction = endFraction,
                widthPx = widthPx,
                onDrag = { deltaPx ->
                    val deltaMs = (deltaPx / widthPx * duration).toLong()
                    val minGap = state.frameMs * 2
                    val next = (state.trimEndMs + deltaMs).coerceIn(state.trimStartMs + minGap, duration)
                    latestTrim(state.trimStartMs, next)
                }
            )
        }

        if (state.hasSeparateAudio) {
            LaneLabel("Audio · ${state.audioTrackName ?: "external track"}", SquishColors.Teal)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LANE_HEIGHT)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SquishColors.Surface)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaMs = (dragAmount / size.width * duration).toLong()
                            // Dragging the waveform later in time means the picture
                            // now meets an earlier part of the track.
                            latestOffset(-deltaMs)
                        }
                    }
            ) {
                val offsetFraction = (state.audioOffsetMs.toFloat() / duration)
                WaveformCanvas(
                    waveform = state.audioWaveform,
                    color = SquishColors.Teal,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset((-offsetFraction * widthPx).toInt(), 0) }
                        .padding(vertical = 6.dp)
                )
                MarkerTicks(state.markers, duration)
                Playhead(playheadFraction, widthPx)
            }
        }
    }
}

@Composable
private fun LaneLabel(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.width(3.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Text(text, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun BoxScope.DarkenOverlay(fraction: Float, alignEnd: Boolean) {
    if (fraction <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
            .background(SquishColors.Background.copy(alpha = 0.7f))
    )
}

@Composable
private fun BoxScope.MarkerTicks(markers: List<Long>, durationMs: Long) {
    markers.forEach { marker ->
        val fraction = (marker.toFloat() / durationMs).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(SquishColors.Yellow))
        }
    }
}

@Composable
private fun BoxScope.Playhead(fraction: Float, widthPx: Int) {
    Box(
        modifier = Modifier
            .offset { IntOffset((fraction * widthPx).toInt(), 0) }
            .width(2.dp)
            .fillMaxHeight()
            .background(SquishColors.TextPrimary)
            .align(Alignment.CenterStart)
    )
}

@Composable
private fun BoxScope.TrimHandle(fraction: Float, widthPx: Int, onDrag: (Float) -> Unit) {
    val latestDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((fraction * widthPx).toInt() - (HANDLE_WIDTH.toPx() / 2).toInt(), 0) }
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .align(Alignment.CenterStart)
            .clip(RoundedCornerShape(5.dp))
            .background(SquishColors.Coral)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    latestDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SquishColors.Background.copy(alpha = 0.6f))
        )
    }
}
