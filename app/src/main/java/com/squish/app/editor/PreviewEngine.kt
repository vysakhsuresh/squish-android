@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.editor

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Effect
import java.util.concurrent.atomic.AtomicReference
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.LiveLookEffect
import com.squish.app.media.effects.Grade
import com.squish.app.media.effects.MaskEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import com.squish.app.media.LiveCaptionOverlay
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Transform
import com.squish.app.timeline.TransitionType
import kotlin.math.abs

/** How one video surface should be drawn this frame. */
data class SurfaceDraw(
    val visible: Boolean = false,
    val alpha: Float = 1f,
    /** Slide: 0 is in place, 1 is one full width off to the right. */
    val translateXFraction: Float = 0f,
    /** Wipe: the fraction of the width revealed from the left edge. */
    val revealFraction: Float = 1f,
    /** The incoming shot of a transition draws over the outgoing one. */
    val zIndex: Int = 0,
    /** The clip's own placement, animated if it carries keyframes. */
    val transform: Transform = Transform.Identity
)

/** Where an overlay layer sits this frame. */
data class OverlayPlacement(
    val layer: Int,
    val visible: Boolean = false,
    val opacity: Float = 1f,
    val transform: Transform = Transform.Identity
)

/** One reading of the transport, and everything the UI needs to draw the frame. */
data class PreviewFrame(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    /** True when the playhead is over empty space: the picture is legitimately black. */
    val inGap: Boolean = false,
    val surfaceA: SurfaceDraw = SurfaceDraw(),
    val surfaceB: SurfaceDraw = SurfaceDraw(),
    /** Dip to black: how much black sits over the picture right now. */
    val blackVeil: Float = 0f,
    val overlays: List<OverlayPlacement> = emptyList()
)

/**
 * Plays the timeline, composited.
 *
 * Timeline time is the authority: the covering clip is looked up by time every
 * tick, so a clip that moves plays at its new position immediately; each player
 * holds a whole source file rather than a clipped window, so its position *is*
 * source time; empty space is a real state with the clock still running.
 *
 * The base track runs as **A/B roll**, the same way the exporter builds it and the
 * same way an NLE has always done dissolves. A transition needs two shots on
 * screen at once and one player can only show one, so consecutive clips are dealt
 * onto two players by index parity - which guarantees any two overlapping
 * neighbours are on different players. Where they overlap, the transition is a
 * blend between the two surfaces. Overlay layers get a player each above them.
 *
 * The surfaces are TextureViews, not SurfaceViews. A SurfaceView is punched
 * through the window and composited by the system, so it ignores view alpha,
 * transforms and clipping outright - every dissolve, slide and picture-in-picture
 * here would silently do nothing on one.
 */
class PreviewEngine(private val context: Context) {

    val baseA: ExoPlayer = newPlayer()
    val baseB: ExoPlayer = newPlayer()

    /**
     * True once [release] has run.
     *
     * ExoPlayer throws on almost anything asked of it after release, and there is
     * no way to ask it whether it has been released. Leaving the editor tears this
     * down while a tick, a seek or a tap on the picture may still be in flight, so
     * every way in checks this first. The alternative is a crash on the way out of
     * the screen, which is the worst possible moment for one - the work is done and
     * the user is already leaving.
     */
    private var released = false

    private val overlayPlayers = LinkedHashMap<Int, ExoPlayer>()
    private val audioPlayers = LinkedHashMap<String, ExoPlayer>()
    private val audioSources = HashMap<String, String>()

    /** Sounds positioned since the last jump: each is seeked once, on the way in. */
    private val primed = HashSet<String>()

    private var rollA: List<Clip> = emptyList()
    private var rollB: List<Clip> = emptyList()
    private var overlayClips: List<Clip> = emptyList()
    private var layers: List<Int> = emptyList()
    private var audioClips: List<Clip> = emptyList()
    private var captions: List<TextOverlayItem> = emptyList()

    private var fallbackUri: Uri? = null
    private var proxyUri: Uri? = null

    private val loadedUri = HashMap<String, String>()   // surface key -> source uri
    private val activeClip = HashMap<String, String>()  // surface key -> clip id

    private var clockClipId: String? = null
    private var clockKey: String = KEY_A

    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var playing: Boolean = false
    private var inGap: Boolean = false
    /**
     * The grade every surface is drawing with, read by the shader on each frame.
     * Changing it is a write here, never a pipeline rebuild - see [LiveLookEffect].
     */
    private val liveGrade = AtomicReference(IDENTITY_GRADE)

    /**
     * Framing, previewed rather than promised.
     *
     * Rotation and crop were export-only: the preview box changed shape when you
     * chose 9:16, but the picture inside it did not, so the one thing the setting
     * is for was the one thing you could not see until afterwards. These are the
     * same two Media3 effects the exporter builds, in the same order.
     */
    private var rotationDegrees: Int = 0
    private var cropRatio: Float? = null

    /**
     * What rate each surface is currently running at.
     *
     * Speed is a property of a clip now, so it changes as the playhead crosses
     * from one shot to the next and again continuously through a ramp. Tracked per
     * surface because ExoPlayer's setPlaybackSpeed is not free: pushing the same
     * number sixteen times a second would restart the audio stretcher on every
     * tick and turn a ramp into a rattle.
     */
    private val appliedSpeed = HashMap<String, Float>()

    /**
     * What each surface's effect chain currently is. Handing a player a new effect
     * list rebuilds its GL pipeline and drops frames, so it is only done when the
     * chain would actually differ - which for most clips is never.
     */
    private val appliedEffects = HashMap<String, String>()

    /** Each surface's captions, in that surface's clock, read by its caption layer every frame. */
    private val liveCaptions = HashMap<String, AtomicReference<List<TextOverlayItem>>>()

    /** Set when what a paused frame shows has changed; see [setTimeline]. */
    private var pendingRedraw = false

    private var anchorTimelineMs: Long = 0
    private var anchorWallMs: Long = SystemClock.elapsedRealtime()

    private fun newPlayer() = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    /**
     * A player for an added sound, built differently from a video surface on
     * purpose. Two settings, and both of them are the reason music used to break
     * up for the first few plays:
     *
     * - **CLOSEST_SYNC rather than EXACT.** An exact seek into a compressed audio
     *   stream decodes and throws away everything from the previous sync sample to
     *   land on the right frame. On a cold file that work lands in the same moment
     *   playback is meant to start. Music does not need frame accuracy - a few
     *   milliseconds is inaudible, and the offset controls exist for the rest.
     * - **A short buffer-for-playback.** The default waits for 2.5 seconds of audio
     *   before it will start. Starting from a quarter second means the decoder is
     *   ahead of the playhead by the time the cue is due rather than racing it.
     */
    private fun newAudioPlayer() = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2_000,
                    /* maxBufferMs = */ 15_000,
                    /* bufferForPlaybackMs = */ 250,
                    /* bufferForPlaybackAfterRebufferMs = */ 500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()
        .apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }

    /**
     * The player for an overlay layer, created the first time that layer appears.
     * Muted, because the export removes an overlay's audio too.
     *
     * The current grade is applied on creation: a layer added after the look was
     * chosen would otherwise be the one ungraded surface on screen.
     */
    fun overlayPlayer(layer: Int): ExoPlayer = overlayPlayers.getOrPut(layer) {
        // Created bare; the next syncSurface gives it the grade and any chroma key.
        // Forgetting that is how a layer added after a look was chosen ended up the
        // one ungraded surface on screen.
        appliedEffects.remove(KEY_OVERLAY + layer)
        newPlayer().apply { volume = 0f }
    }

    // ---- Timeline ---------------------------------------------------------------

    fun setTimeline(
        videoClips: List<Clip>,
        audioClips: List<Clip>,
        captions: List<TextOverlayItem>,
        fallbackUri: Uri,
        proxyUri: Uri?,
        muteOriginal: Boolean,
        originalVolume: Float,
        grade: Grade,
        rotationDegrees: Int,
        cropRatio: Float?
    ) {
        if (released) return
        // A paused picture does not redraw by itself, so a restyled caption or a
        // new grade would not show until play. The next tick, once every surface
        // has been handed the change, asks for a fresh frame.
        if (captions != this.captions || grade != liveGrade.get()) pendingRedraw = true
        this.captions = captions
        applyFraming(rotationDegrees, cropRatio)
        val base = videoClips.filter { !it.isOverlay }.sortedBy { it.timelineStartMs }

        // Index parity, exactly as CompositionFactory deals the export's two rolls.
        // Matching it is not an aesthetic choice: if preview and export disagreed
        // about which shot is on which roll, a dissolve would preview one way and
        // render the other.
        rollA = base.filterIndexed { i, _ -> i % 2 == 0 }
        rollB = base.filterIndexed { i, _ -> i % 2 == 1 }

        overlayClips = videoClips.filter { it.isOverlay }.sortedBy { it.layer }
        layers = overlayClips.map { it.layer }.distinct().sorted()

        // A layer that has been deleted or dropped back onto the base track is no
        // longer walked by syncOverlays, so nothing would ever tell its player to
        // stop - it would keep playing, unseen and unheard but decoding.
        overlayPlayers.forEach { (layer, player) ->
            if (layer !in layers && player.playWhenReady) player.pause()
        }

        this.audioClips = audioClips
        this.fallbackUri = fallbackUri
        this.proxyUri = proxyUri

        applyGrade(grade)
        baseA.volume = if (muteOriginal) 0f else originalVolume
        baseB.volume = if (muteOriginal) 0f else originalVolume

        durationMs = maxOf(
            videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
        )
        reconcileAudioPlayers()
        // Positions every sound where the playhead already is, so the decoder is
        // warm and parked on the right sample before anyone presses play.
        primeAudio(positionMs)
    }

    /** Re-frames every surface, but only when the framing has actually changed. */
    private fun applyFraming(rotation: Int, crop: Float?) {
        if (rotation == rotationDegrees && crop == cropRatio) return
        rotationDegrees = rotation
        cropRatio = crop
        // Every surface now disagrees with the chain it is running.
        appliedEffects.clear()
    }

    /**
     * Puts one player at the rate its clip calls for, if that has moved enough to
     * be worth the interruption. A thousandth is well under what anyone can hear.
     */
    private fun setSpeed(key: String, player: ExoPlayer, wanted: Float) {
        val safe = wanted.coerceIn(0.1f, 10f)
        val current = appliedSpeed[key]
        if (current != null && abs(current - safe) < 0.001f) return
        appliedSpeed[key] = safe
        runCatching { player.setPlaybackSpeed(safe) }
    }

    /**
     * Grades every surface with the effects the export will use, so choosing a look
     * is something you see rather than something you guess at.
     *
     * Only re-applied when the grade changes: handing a player a new effect list
     * rebuilds its GL pipeline and drops frames, and dragging the strength slider
     * would otherwise do that on every pixel of travel.
     *
     * Guarded because setVideoEffects is unstable API. If a device refuses it the
     * preview plays ungraded and the export still applies the look - the feature
     * degrades instead of breaking.
     */
    private fun applyGrade(grade: Grade) {
        // Picked up by the next frame on every surface. No rebuild: that was what
        // froze the picture for the length of every slider drag.
        liveGrade.set(grade)
    }

    /**
     * Gives one surface the effects its current clip needs: the green screen key,
     * then the grade.
     *
     * This is the same ChromaKeyEffect and the same grade shader the export uses,
     * handed to the preview player - so the key you tune is the key that renders,
     * to the pixel, rather than an approximation of it.
     */
    private fun applySurfaceEffects(surfaceKey: String, player: ExoPlayer, clip: Clip?) {
        val chroma = clip?.chromaKey
        val mask = clip?.mask
        // Only captions that overlap this clip, shifted into its own clock, handed
        // to the surface's caption layer as a value - see [LiveCaptionOverlay].
        val visible = if (clip == null) emptyList() else captions
            .filter { it.endMs > clip.timelineStartMs && it.startMs < clip.timelineEndMs }
            .map { it.shiftedInto(clip) }
        val captionsHere = liveCaptions.getOrPut(surfaceKey) { AtomicReference(emptyList()) }
        captionsHere.set(visible)

        // Neither the grade nor the captions are in here: both live in references
        // the pipeline reads each frame, so changing them needs no rebuild.
        // Everything that is here still needs one, but none of it moves on every
        // tap or every frame of a drag.
        val signature = "$chroma|$mask|$rotationDegrees|$cropRatio"
        if (appliedEffects[surfaceKey] == signature) return
        appliedEffects[surfaceKey] = signature

        val effects = buildList<Effect> {
            // Keyed first, then masked, matching the export's order exactly.
            chroma?.let { add(ChromaKeyEffect(it)) }
            // The preview player holds the whole source file, so its clock is source time.
            mask?.let { add(MaskEffect(it, clip?.sourceInMs ?: 0L, timesAreSourceTime = true)) }
            // Then framing, then colour - the exporter's order exactly. Grading
            // before a crop would grade pixels that are about to be thrown away,
            // which changes nothing visible but does change what is measured by
            // anything averaging the frame.
            if (rotationDegrees != 0) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(rotationDegrees.toFloat())
                        .build()
                )
            }
            cropRatio?.let {
                add(Presentation.createForAspectRatio(it, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }
            // Always present, even ungraded, so the pipeline exists from the first
            // frame and the first touch of a slider changes a value rather than
            // building one mid-playback.
            add(LiveLookEffect(liveGrade))
            // Always present for the same reason: the first title added mid-play
            // is drawn by the next frame instead of rebuilding the pipeline.
            val overlays: List<TextureOverlay> = listOf(LiveCaptionOverlay(captionsHere))
            add(OverlayEffect(ImmutableList.copyOf(overlays)))
        }
        // Guarded: setVideoEffects is unstable API, and a custom shader can fail to
        // compile on a given driver. Either way the preview falls back to plain
        // playback and the export still applies everything.
        runCatching { player.setVideoEffects(effects) }
    }

    // ---- Transport --------------------------------------------------------------

    fun play() {
        if (released) return
        if (durationMs > 0 && positionMs >= durationMs) seekTo(0)
        playing = true
        anchorTimelineMs = positionMs
        anchorWallMs = SystemClock.elapsedRealtime()
        // Not cleared. Clearing here is what used to make the first play the one
        // that stuttered: every sound would seek from cold at the exact instant it
        // was due. They were positioned when the timeline was set.
        primeAudio(positionMs)
    }

    fun pause() {
        if (released) return
        playing = false
        baseA.pause(); baseB.pause()
        overlayPlayers.values.forEach { it.pause() }
        audioPlayers.values.forEach { it.pause() }
    }

    fun togglePlay() {
        if (released) return
        if (playing) pause() else play()
    }

    fun seekTo(timelineMs: Long) {
        if (released) return
        positionMs = timelineMs.coerceIn(0L, maxOf(durationMs, 0L))
        anchorTimelineMs = positionMs
        anchorWallMs = SystemClock.elapsedRealtime()
        // Forces every surface to re-resolve its clip and seek, which is what makes
        // a scrub land on the right frame of the right shot on every layer at once.
        activeClip.clear()
        clockClipId = null
        primeAudio(positionMs)
    }

    /**
     * Puts the current frame back on screen after the picture changed size.
     *
     * A paused TextureView keeps the frame it had, at the size it had it, so
     * opening a tool panel - which shrinks the preview - left a small stale frame
     * adrift in a bigger box until playback drew a new one. A seek makes the
     * decoder hand over a fresh frame at the new size.
     *
     * Nudged by a millisecond, which is the same frame: a seek to exactly where a
     * player already is gets dropped as a no-op, and draws nothing.
     */
    fun redraw() {
        if (released || playing) return
        (listOf(baseA, baseB) + overlayPlayers.values).forEach { player ->
            if (player.mediaItemCount == 0) return@forEach
            val at = player.currentPosition
            player.seekTo(if (at > 0L) at - 1L else at + 1L)
        }
    }

    /**
     * Parks every sound on the sample it will need, without starting it.
     *
     * Called on load, on seek and on play rather than at the moment a cue is due,
     * because a seek is only free when nothing is waiting on it. This is the whole
     * fix for music breaking up on the first few plays and settling down later: it
     * settled because the file had become warm, so the answer is to warm it
     * deliberately instead of hoping.
     */
    private fun primeAudio(t: Long) {
        audioClips.forEach { clip ->
            val player = audioPlayers[clip.id] ?: return@forEach
            val at = t.coerceIn(clip.timelineStartMs, clip.timelineEndMs.coerceAtLeast(clip.timelineStartMs))
            player.seekTo(clip.sourceAt(at).coerceAtLeast(0L))
            primed.add(clip.id)
        }
    }

    // ---- The clock --------------------------------------------------------------

    fun tick(): PreviewFrame {
        if (released) return PreviewFrame()
        val now = SystemClock.elapsedRealtime()

        val clockPlayer = if (clockKey == KEY_A) baseA else baseB
        val clockClip = (rollA + rollB).firstOrNull { it.id == clockClipId }
        val state = clockPlayer.playbackState
        val driving = clockClip != null && state == Player.STATE_READY && clockPlayer.isPlaying
        val stalled = playing && clockClip != null && state == Player.STATE_BUFFERING

        var t = when {
            !playing -> positionMs
            // Waiting on a decoder is not time passing. Holding the clock keeps
            // sound and picture together through a stall.
            stalled -> positionMs
            // Mapped back through the clip's own curve, so the playhead tracks a
            // ramp instead of racing it and then waiting.
            driving ->
                clockClip.timelineAtSource(clockPlayer.currentPosition)
            // The timeline is played time, so it runs at wall time. Speed lives
            // inside each clip's length rather than on the clock.
            else -> anchorTimelineMs + (now - anchorWallMs)
        }.coerceAtLeast(0L)

        if (playing && durationMs > 0 && t >= durationMs) {
            t = durationMs
            pause()
        }

        positionMs = t
        anchorTimelineMs = t
        anchorWallMs = now

        val (drawA, drawB, veil) = composeBase(t)
        if (pendingRedraw) {
            pendingRedraw = false
            redraw()
        }
        val overlays = syncOverlays(t)
        syncAudio(t, transportRunning = playing && !stalled)

        return PreviewFrame(
            positionMs = t,
            durationMs = durationMs,
            isPlaying = playing,
            inGap = inGap,
            surfaceA = drawA,
            surfaceB = drawB,
            blackVeil = veil,
            overlays = overlays
        )
    }

    // ---- Base track and transitions ---------------------------------------------

    private fun composeBase(t: Long): Triple<SurfaceDraw, SurfaceDraw, Float> {
        val clipA = rollA.lastOrNull { covers(it, t) }
        val clipB = rollB.lastOrNull { covers(it, t) }

        if (clipA == null && clipB == null) {
            inGap = rollA.isNotEmpty() || rollB.isNotEmpty()
            if (baseA.playWhenReady) baseA.pause()
            if (baseB.playWhenReady) baseB.pause()
            return Triple(SurfaceDraw(), SurfaceDraw(), 0f)
        }
        inGap = false

        syncSurface(KEY_A, baseA, clipA, t)
        syncSurface(KEY_B, baseB, clipB, t)

        // Whichever shot was already driving keeps the clock for the whole
        // transition. Handing it over mid-blend would step the playhead by whatever
        // the two players happen to differ by.
        val stillClocking = listOfNotNull(clipA, clipB).any { it.id == clockClipId }
        if (!stillClocking) {
            val next = listOfNotNull(clipA, clipB).minByOrNull { it.timelineStartMs }
            clockClipId = next?.id
            clockKey = if (next != null && clipA?.id == next.id) KEY_A else KEY_B
        }

        if (clipA == null || clipB == null) {
            val onA = clipA != null
            val only = (clipA ?: clipB)!!
            return Triple(
                SurfaceDraw(visible = onA, transform = only.transformAt(t)),
                SurfaceDraw(visible = !onA, transform = only.transformAt(t)),
                0f
            )
        }

        // Both rolls have a shot here, so the two overlap: a transition.
        val incoming = if (clipA.timelineStartMs >= clipB.timelineStartMs) clipA else clipB
        val outgoing = if (incoming === clipA) clipB else clipA
        val overlapMs = (outgoing.timelineEndMs - incoming.timelineStartMs).coerceAtLeast(1L)
        val progress = ((t - incoming.timelineStartMs).toFloat() / overlapMs).coerceIn(0f, 1f)

        val (outDraw, inDraw, veil) = blend(incoming.transitionIn.type, progress)
        // Both shots keep animating through the blend, which is the point of
        // keyframing a transition - a push-in that stalls mid-dissolve is a glitch.
        val outMoved = outDraw.copy(transform = outgoing.transformAt(t))
        val inMoved = inDraw.copy(transform = incoming.transformAt(t))
        val aIsIncoming = incoming === clipA
        return Triple(
            if (aIsIncoming) inMoved else outMoved,
            if (aIsIncoming) outMoved else inMoved,
            veil
        )
    }

    /**
     * How a transition reads, as (outgoing, incoming, black veil). These mirror
     * CompositionFactory.transitionAlphaAt - the preview and the render describe the
     * same blend, one for the screen and one for the compositor.
     */
    private fun blend(type: TransitionType, p: Float): Triple<SurfaceDraw, SurfaceDraw, Float> {
        val under = SurfaceDraw(visible = true, zIndex = 0)
        val over = SurfaceDraw(visible = true, zIndex = 1)
        return when (type) {
            TransitionType.CrossFade ->
                Triple(under, over.copy(alpha = p), 0f)

            // Out to nothing and back up, with black deepest at the midpoint.
            TransitionType.DipToBlack -> Triple(
                under.copy(visible = p < 0.5f),
                over.copy(visible = p >= 0.5f),
                1f - abs(2f * p - 1f)
            )

            TransitionType.SlideLeft ->
                Triple(under, over.copy(translateXFraction = 1f - p), 0f)

            TransitionType.WipeRight ->
                Triple(under, over.copy(revealFraction = p), 0f)

            // Overlapping shots with no transition set: a hard cut to the new one.
            TransitionType.None ->
                Triple(under.copy(visible = false), over, 0f)
        }
    }

    // ---- Overlay layers ----------------------------------------------------------

    private fun syncOverlays(t: Long): List<OverlayPlacement> = layers.map { layer ->
        val clip = overlayClips.lastOrNull { it.layer == layer && covers(it, t) }
        val key = KEY_OVERLAY + layer
        val player = overlayPlayer(layer)
        syncSurface(key, player, clip, t)

        if (clip == null) {
            OverlayPlacement(layer = layer, visible = false)
        } else {
            OverlayPlacement(
                layer = layer,
                visible = true,
                opacity = clip.opacity,
                transform = clip.transformAt(t)
            )
        }
    }

    // ---- Shared surface plumbing --------------------------------------------------

    /** Points one player at whatever clip covers this moment, or parks it. */
    private fun syncSurface(key: String, player: ExoPlayer, clip: Clip?, t: Long) {
        if (clip == null) {
            if (player.playWhenReady) player.pause()
            activeClip.remove(key)
            return
        }

        val source = playbackUriFor(clip) ?: return
        applySurfaceEffects(key, player, clip)
        setSpeed(key, player, clip.speedAt(t))
        val wanted = clip.sourceAt(t).coerceAtLeast(0L)

        when {
            loadedUri[key] != source.toString() -> {
                player.setMediaItem(MediaItem.fromUri(source))
                player.prepare()
                loadedUri[key] = source.toString()
                activeClip[key] = clip.id
                player.seekTo(wanted)
            }
            // A different slice of the same file - a split, or a jump. No reload:
            // the media is already open, so this is a seek and nothing more.
            activeClip[key] != clip.id -> {
                activeClip[key] = clip.id
                player.seekTo(wanted)
            }
            // Zero by construction while this surface drives the clock, so playback
            // is never interrupted by its own correction.
            abs(player.currentPosition - wanted) > VIDEO_RESYNC_MS -> player.seekTo(wanted)
        }

        if (playing && !player.playWhenReady) player.play()
        if (!playing && player.playWhenReady) player.pause()
    }

    private fun covers(clip: Clip, t: Long): Boolean =
        t >= clip.timelineStartMs && t < clip.timelineEndMs

    private fun playbackUriFor(clip: Clip): Uri? {
        val source = clip.uri ?: fallbackUri ?: return null
        // Heavy footage previews from its light copy; export never comes through here.
        return if (proxyUri != null && source == fallbackUri) proxyUri else source
    }

    // ---- Sound --------------------------------------------------------------------

    private fun syncAudio(t: Long, transportRunning: Boolean) {
        audioClips.forEach { clip ->
            val player = audioPlayers[clip.id] ?: return@forEach

            if (!covers(clip, t)) {
                if (player.playWhenReady) player.pause()
                // A cue coming up shortly is positioned now, while nothing is
                // waiting on the seek, so it is buffered and parked on the right
                // sample by the time it is due.
                val untilDue = clip.timelineStartMs - t
                if (untilDue in 1..PREROLL_MS && !primed.contains(clip.id)) {
                    setSpeed(clip.id, player, clip.speedAt(clip.timelineStartMs))
                    player.seekTo(clip.sourceInMs)
                    primed.add(clip.id)
                }
                // Only a cue that is now behind us is worth re-priming, and only
                // once we are past it far enough that a rewind is a real jump.
                if (t >= clip.timelineEndMs || untilDue > PREROLL_MS) primed.remove(clip.id)
                return@forEach
            }

            player.volume = clip.volume
            setSpeed(clip.id, player, clip.speedAt(t))
            val wanted = clip.sourceAt(t).coerceAtLeast(0L)

            if (!primed.contains(clip.id)) {
                // Only reached when a cue was jumped into with no warning - a scrub
                // straight into the middle of it. Everywhere else it is already
                // parked, because chasing drift with seeks forces a re-buffer, the
                // re-buffer causes more drift, and that drift triggers the next
                // seek: a loop that sounds exactly like audio breaking up.
                player.seekTo(wanted)
                primed.add(clip.id)
            } else if (abs(player.currentPosition - wanted) > AUDIO_RESYNC_MS) {
                player.seekTo(wanted)
            }

            if (transportRunning && !player.playWhenReady) player.play()
            if (!transportRunning && player.playWhenReady) player.pause()
        }
    }

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
                    audioPlayers[clip.id] = newAudioPlayer().apply {
                        setMediaItem(MediaItem.fromUri(uri))
                        // Prepared the moment the track is added, so the first play
                        // is not also the first read off storage.
                        prepare()
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
        if (released) return
        released = true
        baseA.release()
        baseB.release()
        overlayPlayers.values.forEach { it.release() }
        audioPlayers.values.forEach { it.release() }
        overlayPlayers.clear()
        audioPlayers.clear()
        audioSources.clear()
        primed.clear()
        appliedSpeed.clear()
    }

    private companion object {
        const val KEY_A = "base-a"
        const val KEY_B = "base-b"
        const val KEY_OVERLAY = "overlay-"

        /** Generous on purpose: correction is for a jump, not for playback. */
        const val AUDIO_RESYNC_MS = 400L
        const val VIDEO_RESYNC_MS = 120L

        /** How far ahead of a cue its decoder is positioned and buffered. */
        const val PREROLL_MS = 2_000L
    }
}

/** A grade that changes nothing: unit gain, no contrast or saturation shift, no look. */
private val IDENTITY_GRADE = Grade(redScale = 1f, greenScale = 1f, blueScale = 1f, contrast = 0f, saturation = 0f)
