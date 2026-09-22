package com.squish.app.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.squish.app.media.effects.Grade
import com.squish.app.timeline.Clip
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay

/**
 * The preview surface, driven by [PreviewEngine].
 *
 * The player's own controller is deliberately switched off. It knows about a
 * playlist, not about a timeline, so leaving it on would give the user two
 * transports fighting over one clock.
 */
@Composable
fun TimelinePreview(
    videoClips: List<Clip>,
    audioClips: List<Clip>,
    fallbackUri: Uri,
    proxyUri: Uri?,
    muteOriginal: Boolean,
    originalVolume: Float,
    grade: Grade,
    playheadMs: Long,
    scrubNonce: Long,
    onPositionChange: (Long) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember { PreviewEngine(context) }
    val latestPosition by rememberUpdatedState(onPositionChange)
    val latestPlaying by rememberUpdatedState(onPlayingChange)

    var frame by remember { mutableStateOf(PlaybackFrame()) }

    DisposableEffect(engine) { onDispose { engine.release() } }

    // Any change to the shape of the edit is pushed straight in. Positions and
    // trims are read fresh on every tick, so this only has to run when the set of
    // clips itself changes.
    val editSignature = remember(videoClips, audioClips, proxyUri, muteOriginal, originalVolume, grade) {
        videoClips.joinToString("|") { "${it.id}@${it.timelineStartMs}:${it.sourceInMs}-${it.sourceOutMs}" } +
            "//" + audioClips.joinToString("|") { "${it.id}@${it.timelineStartMs}:${it.sourceInMs}-${it.sourceOutMs}:${it.volume}" } +
            "//" + proxyUri + muteOriginal + originalVolume + grade
    }

    LaunchedEffect(editSignature, fallbackUri) {
        engine.setTimeline(videoClips, audioClips, fallbackUri, proxyUri, muteOriginal, originalVolume, grade)
    }

    // A deliberate jump - scrubbing the ruler, or a nudge - as opposed to the
    // playhead simply advancing. Only the former should move the players.
    LaunchedEffect(scrubNonce) { engine.seekTo(playheadMs) }

    LaunchedEffect(engine) {
        while (true) {
            val next = engine.tick()
            if (next != frame) {
                if (next.positionMs != frame.positionMs) latestPosition(next.positionMs)
                if (next.isPlaying != frame.isPlaying) latestPlaying(next.isPlaying)
                frame = next
            }
            delay(TICK_MS)
        }
    }

    Box(
        modifier = modifier.clickable(
            // The player's own controller is off, so the picture itself is the
            // play button - which is what people reach for anyway.
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = { engine.togglePlay() }
        )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    player = engine.videoPlayer
                    useController = false
                }
            }
        )

        // Empty space on the timeline is a real part of the edit, and the exported
        // file goes black here. Showing the last frame frozen instead would be a
        // quiet lie about what you are about to render.
        if (frame.inGap) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    "Gap — no clip here",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Transport(
            frame = frame,
            onToggle = { engine.togglePlay() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun Transport(frame: PlaybackFrame, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SquishColors.Background.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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

        Text(
            Timecode.format(frame.positionMs),
            style = MaterialTheme.typography.labelLarge,
            color = SquishColors.TextPrimary
        )
        Text(
            "/ ${Timecode.format(frame.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
    }
}

private const val TICK_MS = 33L
