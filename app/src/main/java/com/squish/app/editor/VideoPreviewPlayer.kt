package com.squish.app.editor

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Plays the picture against the separate audio track exactly where it will land in
 * the export, so a music cue or a synced take can be judged by ear before rendering.
 *
 * Two players rather than one merged source: a second ExoPlayer is plain, stable
 * API, and a watchdog re-seeks it whenever the pair drift more than a frame apart.
 * The export is sample-accurate regardless - it aligns by clipping, not by playback.
 */
@Composable
fun VideoPreviewPlayer(
    videoUri: Uri,
    audioUri: Uri?,
    audioTrimStartMs: Long,
    audioPlacementMs: Long,
    audioSliceDurationMs: Long,
    muteOriginal: Boolean,
    originalVolume: Float,
    audioVolume: Float,
    onPlayheadChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestPlayhead by rememberUpdatedState(onPlayheadChange)
    val latestTrimStart by rememberUpdatedState(audioTrimStartMs)
    val latestPlacement by rememberUpdatedState(audioPlacementMs)
    val latestSlice by rememberUpdatedState(audioSliceDurationMs)

    val videoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = false
        }
    }

    val audioPlayer = remember(audioUri) {
        audioUri?.let {
            ExoPlayer.Builder(context).build().apply {
                setSeekParameters(SeekParameters.EXACT)
                setMediaItem(MediaItem.fromUri(it))
                prepare()
                playWhenReady = false
            }
        }
    }

    // One effect per player. Keying a single effect on both meant that attaching an
    // audio track disposed - and released - the still-in-use video player, which
    // killed the preview the moment music was added.
    DisposableEffect(videoPlayer) {
        onDispose { videoPlayer.release() }
    }
    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer?.release() }
    }

    LaunchedEffect(muteOriginal, originalVolume, audioVolume, audioPlayer) {
        videoPlayer.volume = if (muteOriginal) 0f else originalVolume
        audioPlayer?.volume = audioVolume
    }

    LaunchedEffect(videoPlayer, audioPlayer) {
        while (true) {
            val position = videoPlayer.currentPosition
            latestPlayhead(position)

            if (audioPlayer != null) {
                val start = latestPlacement
                val end = start + latestSlice
                val insideCue = position in start until end.coerceAtLeast(start + 1)

                if (!insideCue) {
                    if (audioPlayer.isPlaying) audioPlayer.pause()
                } else {
                    val target = latestTrimStart + (position - start)
                    if (abs(audioPlayer.currentPosition - target) > DRIFT_TOLERANCE_MS) {
                        audioPlayer.seekTo(target.coerceAtLeast(0L))
                    }
                    if (videoPlayer.isPlaying && !audioPlayer.isPlaying) audioPlayer.play()
                    if (!videoPlayer.isPlaying && audioPlayer.isPlaying) audioPlayer.pause()
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            PlayerView(it).apply {
                player = videoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        }
    )
}

private const val POLL_INTERVAL_MS = 40L
private const val DRIFT_TOLERANCE_MS = 45L
