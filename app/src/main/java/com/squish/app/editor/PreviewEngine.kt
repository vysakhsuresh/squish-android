package com.squish.app.editor

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.squish.app.media.effects.ColorGrade
import com.squish.app.media.effects.Grade
import com.squish.app.timeline.Clip
import kotlin.math.abs

/** One reading of the transport, handed to the UI each tick. */
data class PlaybackFrame(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    /** True when the playhead is over empty space: the picture is legitimately black. */
    val inGap: Boolean = false
)

/**
 * Plays the timeline.
 *
 * The previous preview was an ExoPlayer *playlist* - the clips glued end to end in
 * order. A playlist has no notion of when a clip sits or that empty space can exist
 * between two of them, so moving a clip changed nothing about when it played, and
 * the reported playhead was "how far into the playlist we are", which is a
 * different number from "where we are on the timeline" the moment anything is
 * dragged. That single mismatch is why repositioning appeared to do nothing and why
 * the playhead wandered through empty space.
 *
 * This runs the other way round. Timeline time is the authority:
 *
 *  - the covering clip is looked up by time, every tick, so a clip that moves plays
 *    at its new position immediately;
 *  - the video player holds the whole source file, never a clipped window, so its
 *    position *is* source time and maps to timeline time by one addition;
 *  - empty space is a real state - the picture goes black and the clock keeps
 *    running, because that is what the exported file will do;
 *  - every sound is an independent player positioned against the same clock, which
 *    is what makes any number of overlapping tracks work.
 */
class PreviewEngine(private val context: Context) {

    val videoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    private val audioPlayers = LinkedHashMap<String, ExoPlayer>()
    private val audioSources = HashMap<String, String>()

    /**
     * Sounds that have been positioned since the last jump. A clip is seeked once,
     * on the way in, and then left alone.
     */
    private val primed = HashSet<String>()

    private var videoClips: List<Clip> = emptyList()
    private var audioClips: List<Clip> = emptyList()
    private var fallbackUri: Uri? = null
    private var proxyUri: Uri? = null

    private var loadedVideoUri: String? = null
    private var activeClipId: String? = null
    private var appliedGrade: Grade? = null

    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var playing: Boolean = false
    private var inGap: Boolean = false

    private var anchorTimelineMs: Long = 0
    private var anchorWallMs: Long = SystemClock.elapsedRealtime()

    // ---- Timeline ---------------------------------------------------------------

    fun setTimeline(
        videoClips: List<Clip>,
        audioClips: List<Clip>,
        fallbackUri: Uri,
        proxyUri: Uri?,
        muteOriginal: Boolean,
        originalVolume: Float,
        grade: Grade
    ) {
        this.videoClips = videoClips.sortedBy { it.timelineStartMs }
        this.audioClips = audioClips
        this.fallbackUri = fallbackUri
        this.proxyUri = proxyUri

        applyGrade(grade)
        videoPlayer.volume = if (muteOriginal) 0f else originalVolume
        durationMs = maxOf(
            this.videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
        )
        reconcileAudioPlayers()
    }

    /**
     * Grades the preview with the same effects the export will use, so choosing a
     * look is a thing you see rather than a thing you guess at and discover later.
     *
     * Only re-applied when the grade actually changes: handing the player a new
     * effect list rebuilds its GL pipeline, which drops frames, and dragging the
     * intensity slider would otherwise do that on every pixel of travel.
     *
     * Guarded because setVideoEffects is an unstable API. If a device or a future
     * version refuses it, the preview simply plays ungraded - the export still
     * applies the look, so the feature degrades instead of breaking.
     */
    private fun applyGrade(grade: Grade) {
        if (grade == appliedGrade) return
        appliedGrade = grade
        runCatching { videoPlayer.setVideoEffects(ColorGrade.effects(grade)) }
    }

    // ---- Transport --------------------------------------------------------------

    fun play() {
        if (durationMs > 0 && positionMs >= durationMs) seekTo(0)
        playing = true
        anchorTimelineMs = positionMs
        anchorWallMs = SystemClock.elapsedRealtime()
        // Everything re-positions once on the way in rather than chasing the clock.
        primed.clear()
    }

    fun pause() {
        playing = false
        videoPlayer.pause()
        audioPlayers.values.forEach { it.pause() }
    }

    fun togglePlay() = if (playing) pause() else play()

    fun seekTo(timelineMs: Long) {
        positionMs = timelineMs.coerceIn(0L, maxOf(durationMs, 0L))
        anchorTimelineMs = positionMs
        anchorWallMs = SystemClock.elapsedRealtime()
        primed.clear()
        // Forces the next tick to re-resolve the covering clip and seek to it,
        // which is also what makes a scrub land on the right frame of the right shot.
        activeClipId = null
    }

    // ---- The clock --------------------------------------------------------------

    /**
     * Advances everything one step and reports where we are. Called on a short
     * timer by the UI; all the scheduling decisions live here.
     */
    fun tick(): PlaybackFrame {
        val now = SystemClock.elapsedRealtime()
        val active = videoClips.firstOrNull { it.id == activeClipId }

        val state = videoPlayer.playbackState
        val videoDriving = active != null && state == Player.STATE_READY && videoPlayer.isPlaying

        // Waiting on the decoder is not the same as time passing. Holding the clock
        // keeps sound and picture together through a stall instead of letting the
        // audio sprint ahead and then get yanked back.
        val stalled = playing && active != null && state == Player.STATE_BUFFERING

        var t = when {
            !playing -> positionMs
            stalled -> positionMs
            // The picture's own clock is the most accurate one available, and using
            // it means the playhead can never disagree with the frame on screen.
            videoDriving && active != null ->
                active.timelineStartMs + (videoPlayer.currentPosition - active.sourceInMs)
            // Over empty space there is no picture to take time from, so wall time
            // carries the playhead across the gap.
            else -> anchorTimelineMs + (now - anchorWallMs)
        }.coerceAtLeast(0L)

        if (playing && durationMs > 0 && t >= durationMs) {
            t = durationMs
            pause()
        }

        positionMs = t
        anchorTimelineMs = t
        anchorWallMs = now

        syncVideo(t)
        syncAudio(t, transportRunning = playing && !stalled)

        return PlaybackFrame(positionMs = t, durationMs = durationMs, isPlaying = playing, inGap = inGap)
    }

    // ---- Picture ----------------------------------------------------------------

    private fun syncVideo(t: Long) {
        val clip = baseClipAt(t)

        if (clip == null) {
            // Real empty space. Black picture, clock still running - exactly what
            // the exported file does here.
            inGap = videoClips.isNotEmpty()
            activeClipId = null
            if (videoPlayer.playWhenReady) videoPlayer.pause()
            return
        }

        inGap = false
        val source = playbackUriFor(clip) ?: return
        val wanted = (clip.sourceInMs + (t - clip.timelineStartMs)).coerceAtLeast(0L)

        when {
            loadedVideoUri != source.toString() -> {
                videoPlayer.setMediaItem(MediaItem.fromUri(source))
                videoPlayer.prepare()
                loadedVideoUri = source.toString()
                activeClipId = clip.id
                videoPlayer.seekTo(wanted)
            }
            // A different slice of the same file - a split, or a jump. No reload:
            // the media is already open, so this is a seek and nothing more.
            activeClipId != clip.id -> {
                activeClipId = clip.id
                videoPlayer.seekTo(wanted)
            }
            // While the picture is driving the clock this difference is zero by
            // construction, so playback is never interrupted by its own correction.
            abs(videoPlayer.currentPosition - wanted) > VIDEO_RESYNC_MS -> {
                videoPlayer.seekTo(wanted)
            }
        }

        if (playing && !videoPlayer.playWhenReady) videoPlayer.play()
        if (!playing && videoPlayer.playWhenReady) videoPlayer.pause()
    }

    /**
     * The base clip under a given moment. Later clips win where two overlap, which
     * is what a transition looks like on the strip.
     */
    private fun baseClipAt(t: Long): Clip? =
        videoClips.lastOrNull { it.layer == 0 && t >= it.timelineStartMs && t < it.timelineEndMs }
            ?: videoClips.lastOrNull { t >= it.timelineStartMs && t < it.timelineEndMs }

    private fun playbackUriFor(clip: Clip): Uri? {
        val source = clip.uri ?: fallbackUri ?: return null
        // Heavy footage previews from its light copy; export never comes through here.
        return if (proxyUri != null && source == fallbackUri) proxyUri else source
    }

    // ---- Sound ------------------------------------------------------------------

    private fun syncAudio(t: Long, transportRunning: Boolean) {
        audioClips.forEach { clip ->
            val player = audioPlayers[clip.id] ?: return@forEach
            val inside = t >= clip.timelineStartMs && t < clip.timelineEndMs

            if (!inside) {
                if (player.playWhenReady) player.pause()
                primed.remove(clip.id)
                return@forEach
            }

            player.volume = clip.volume
            val wanted = (clip.sourceInMs + (t - clip.timelineStartMs)).coerceAtLeast(0L)

            if (!primed.contains(clip.id)) {
                // One seek on the way in, then let it run on its own clock.
                //
                // The previous build re-seeked whenever the two players drifted 45 ms
                // apart. Every seek forces a re-buffer, a re-buffer causes more drift,
                // and that drift triggers the next seek - a feedback loop that sounds
                // exactly like audio breaking up, and that only quietens once the file
                // is warm in the page cache. That is the whole story behind "it plays
                // properly on the fourth or fifth try".
                player.seekTo(wanted)
                primed.add(clip.id)
            } else if (abs(player.currentPosition - wanted) > AUDIO_RESYNC_MS) {
                // Two media clocks at 1x drift by a few milliseconds a minute, so in
                // practice this only fires after a scrub - never during playback.
                player.seekTo(wanted)
            }

            if (transportRunning && !player.playWhenReady) player.play()
            if (!transportRunning && player.playWhenReady) player.pause()
        }
    }

    /** One player per sound, created when it appears and released when it goes. */
    private fun reconcileAudioPlayers() {
        val wanted = audioClips.associateBy { it.id }

        (audioPlayers.keys.toList() - wanted.keys).forEach { id ->
            audioPlayers.remove(id)?.release()
            audioSources.remove(id)
            primed.remove(id)
        }

        audioClips.forEach { clip ->
            val uri = clip.uri ?: return@forEach
            val existing = audioPlayers[clip.id]
            when {
                existing == null -> {
                    audioPlayers[clip.id] = ExoPlayer.Builder(context).build().apply {
                        setSeekParameters(SeekParameters.EXACT)
                        setMediaItem(MediaItem.fromUri(uri))
                        // Prepared the moment the track is added, so the first play
                        // is not also the first read off storage.
                        prepare()
                        playWhenReady = false
                        volume = clip.volume
                    }
                    audioSources[clip.id] = uri.toString()
                }
                audioSources[clip.id] != uri.toString() -> {
                    existing.setMediaItem(MediaItem.fromUri(uri))
                    existing.prepare()
                    audioSources[clip.id] = uri.toString()
                    primed.remove(clip.id)
                }
            }
        }
    }

    fun release() {
        videoPlayer.release()
        audioPlayers.values.forEach { it.release() }
        audioPlayers.clear()
        audioSources.clear()
        primed.clear()
    }

    private companion object {
        /** Generous on purpose: correction is for a jump, not for playback. */
        const val AUDIO_RESYNC_MS = 400L
        const val VIDEO_RESYNC_MS = 120L
    }
}
