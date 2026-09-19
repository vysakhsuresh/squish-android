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
 * Preview that plays the video against a separately recorded audio track at the
 * current sync offset, so alignment can be judged by ear before exporting.
 *
 * Two players rather than one merged source: a second ExoPlayer for the external
 * track is a plain, stable API, and a drift watchdog re-seeks it whenever the two
 * slip more than a frame apart. The exported file is sample-accurate regardless -
 * the exporter aligns by clipping, not by playback timing.
 */
@Composable
fun VideoPreviewPlayer(
    videoUri: Uri,
    audioUri: Uri?,
    audioOffsetMs: Long,
    muteOriginal: Boolean,
    originalVolume: Float,
    audioVolume: Float,
    onPlayheadChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestPlayhead by rememberUpdatedState(onPlayheadChange)
    val latestOffset by rememberUpdatedState(audioOffsetMs)

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

    DisposableEffect(videoPlayer, audioPlayer) {
        onDispose {
            videoPlayer.release()
            audioPlayer?.release()
        }
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
                val target = position + latestOffset
                if (target < 0) {
                    // The external track has no material this early; hold it until
                    // the picture reaches the point where the two overlap.
                    if (audioPlayer.isPlaying) audioPlayer.pause()
                } else {
                    if (abs(audioPlayer.currentPosition - target) > DRIFT_TOLERANCE_MS) {
                        audioPlayer.seekTo(target)
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
