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
import com.squish.app.timeline.Clip
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Plays the edit, not the source file.
 *
 * Each timeline clip becomes a clipped MediaItem in a playlist, so trimming,
 * splitting and reordering are all reflected the moment they happen. Previously
 * this player was handed the raw Uri, which is why a trimmed clip still played
 * the whole video back.
 *
 * A separate audio track runs on its own player against the same clock, with a
 * watchdog re-seeking it whenever the two drift more than a frame apart. Export
 * is sample-accurate regardless; it aligns by clipping, not by playback.
 */
@Composable
fun VideoPreviewPlayer(
    videoClips: List<Clip>,
    fallbackUri: Uri,
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

    val ordered = remember(videoClips) { videoClips.sortedBy { it.timelineStartMs } }

    // Rebuild only when the edit actually changes shape, not on every recomposition.
    val editSignature = remember(ordered) {
        ordered.joinToString("|") { "${it.id}@${it.sourceInMs}-${it.sourceOutMs}" }
    }

    val videoPlayer = remember(fallbackUri) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
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

    DisposableEffect(videoPlayer) { onDispose { videoPlayer.release() } }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer?.release() } }

    LaunchedEffect(editSignature, fallbackUri) {
        val items = ordered.map { clip ->
            MediaItem.Builder()
                .setUri(clip.uri ?: fallbackUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInMs)
                        .setEndPositionMs(clip.sourceOutMs.coerceAtLeast(clip.sourceInMs))
                        .build()
                )
                .build()
        }.ifEmpty { listOf(MediaItem.fromUri(fallbackUri)) }

        // Hold our place across a re-trim so the preview does not jump to the top
        // every time a handle moves.
        val keepIndex = videoPlayer.currentMediaItemIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
        val keepPosition = videoPlayer.currentPosition.coerceAtLeast(0L)

        videoPlayer.setMediaItems(items)
        videoPlayer.prepare()
        if (keepIndex > 0 || keepPosition > 0) {
            runCatching { videoPlayer.seekTo(keepIndex, keepPosition) }
        }
    }

    LaunchedEffect(muteOriginal, originalVolume, audioVolume, audioPlayer) {
        videoPlayer.volume = if (muteOriginal) 0f else originalVolume
        audioPlayer?.volume = audioVolume
    }

    LaunchedEffect(videoPlayer, audioPlayer, editSignature) {
        while (true) {
            // Position on the timeline, not within the current clip: everything
            // before the playing item has already gone by.
            val index = videoPlayer.currentMediaItemIndex
            val elapsedBefore = ordered.take(index).sumOf { it.durationMs }
            val position = elapsedBefore + videoPlayer.currentPosition
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
