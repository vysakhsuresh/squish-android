@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.editor

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.squish.app.media.ExportPlan
import com.squish.app.media.StillClips
import com.squish.app.media.audio.GainProcessor
import com.squish.app.media.audio.VoiceProcessor
import com.squish.app.media.effects.BackgroundEffect
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.FxEffect
import com.squish.app.media.effects.LayerBlendEffect
import com.squish.app.media.effects.LiveLayerBlendEffect
import com.squish.app.media.effects.LiveLookEffect
import com.squish.app.media.effects.Looks
import com.squish.app.media.effects.MaskEffect
import com.squish.app.media.effects.PremultiplyEffect
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.LayerBlend
import com.squish.app.timeline.Mask
import com.squish.app.timeline.Transform
import com.squish.app.timeline.TransitionType
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * How one video surface should be drawn this frame: the clip's own placement,
 * and its part in a transition as the export draws it (ExportPlan.Draw) -
 * the same fields, from the same function, so the two cannot disagree.
 */
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
    val transform: Transform = Transform.Identity,
    /**
     * The stabilizer's correction (Clip.stabilizerAt), applied to the decoded
     * picture itself - before its turn, crop and fit, as the file applies it -
     * not with the placement on the canvas.
     */
    val stabilizer: Transform = Transform.Identity,
    /**
     * The clip's own mirror and quarter turns, applied to the view inside the
     * canvas (see Clip.quarterTurns); and the decoded picture's shape, which a
     * turned view is fitted by - an unturned base view fills the canvas as it
     * always has.
     */
    val mirrored: Boolean = false,
    val quarterTurns: Int = 0,
    /** The clip's own crop, applied to the view by the preview (see ClipCrop). */
    val crop: ClipCrop? = null,
    /** Which clip is drawn, so the preview can show it plain while its picture is worked on. */
    val clipId: String? = null,
    /**
     * The decoded picture's shape, width over height, once the player has
     * said: the surface is laid out at it, fitted into the picture's frame,
     * so a shot of another shape than the edit's sits where the file puts it
     * and its crop window is measured on its own picture.
     */
    val aspect: Float? = null,
    /** Slide up or down: 1 is one full height off the bottom. */
    val translateYFraction: Float = 0f,
    /** Zoom: how much larger than the canvas the transition draws it. */
    val scale: Float = 1f,
    /** Where the drawn rectangle begins across and down, and ends down (the end across is [revealFraction]). */
    val revealFrom: Float = 0f,
    val revealFromY: Float = 0f,
    val revealToY: Float = 1f,
    /** Flash, glow, dip to white: how far towards white the picture is mixed. */
    val white: Float = 0f
) {
    companion object {
        /** [draw] as the screen draws it, on top ([zIndex] 1) or under. */
        fun of(draw: ExportPlan.Draw, onTop: Boolean): SurfaceDraw = SurfaceDraw(
            visible = draw.alpha > 0f,
            alpha = draw.alpha,
            translateXFraction = draw.shiftX,
            revealFraction = draw.keepTo,
            zIndex = if (onTop) 1 else 0,
            translateYFraction = draw.shiftY,
            scale = draw.scale,
            revealFrom = draw.keepFrom,
            revealFromY = draw.keepFromY,
            revealToY = draw.keepToY,
            white = draw.white
        )
    }
}

/** Where an overlay layer sits this frame. */
data class OverlayPlacement(
    val layer: Int,
    /** Drawn this frame: a clip covers the moment and its first frame is on the surface. */
    val visible: Boolean = false,
    val opacity: Float = 1f,
    val transform: Transform = Transform.Identity,
    /** The stabilizer's correction, on the decoded picture before its turn and crop (see SurfaceDraw.stabilizer). */
    val stabilizer: Transform = Transform.Identity,
    /** The clip's own mirror and quarter turns, applied to the view inside its fitted box. */
    val mirrored: Boolean = false,
    val quarterTurns: Int = 0,
    /** The clip's own draw at this moment - a transition over its head, as the export draws it. */
    val draw: ExportPlan.Draw = ExportPlan.PLAIN,
    /**
     * The layer's picture shape, once its decoder has said. The surface is laid
     * out at this shape, fitted into the canvas, which is where the export's
     * Presentation puts it too.
     */
    val aspect: Float? = null,
    /** A clip covers this moment on this layer, whether or not it has drawn yet. */
    val covers: Boolean = false,
    /** Which clip that is, so the box on the picture can find the shape it is drawn at. */
    val clipId: String? = null,
    /** The clip's own crop, applied to the view by the preview (see ClipCrop). */
    val crop: ClipCrop? = null
)

/** One reading of the transport, and everything the UI needs to draw the frame. */
data class PreviewFrame(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    /** True when the playhead is over empty space on the base track: the picture is legitimately black. */
    val inGap: Boolean = false,
    /** True when that empty space is after the last base clip, not a hole between two. */
    val pictureEnded: Boolean = false,
    val surfaceA: SurfaceDraw = SurfaceDraw(),
    val surfaceB: SurfaceDraw = SurfaceDraw(),
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
 * Each surface's effect chain is installed once, when its player is made, and
 * never changed. Everything that varies - the grade, a mask, a key, background
 * removal, the effects library - is read by the shaders from a reference every
 * frame. Swapping a chain under a loaded player means stopping and reloading it,
 * and every freeze this preview has had came from doing that: per slider tick for
 * a mask, per template, and on Rotate 90, where the wedged pipeline then held the
 * main thread for two seconds at a time. Rotation and crop are done on screen by
 * the view instead (see TimelinePreview), and captions are drawn in Compose.
 *
 * The surfaces are TextureViews, not SurfaceViews. A SurfaceView is punched
 * through the window and composited by the system, so it ignores view alpha,
 * transforms and clipping outright - every dissolve, slide and picture-in-picture
 * here would silently do nothing on one.
 */
class PreviewEngine(private val context: Context) {

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

    /**
     * Whether a clip's green screen is drawn. Held off while the key's colour
     * is being picked off the picture, so the loupe reads the screen's own
     * green rather than the hole a first guess at the key has already cut.
     */
    private var keyPreview = true

    /**
     * Whether every level is held at nothing: while a voiceover is being taken,
     * so the speaker does not feed the picture's sound back into the mic. The
     * levels are still worked out and set the moment it lifts.
     */
    private var muted = false

    /**
     * What sits in front of one sound player's sink, read every buffer: the
     * boost above the player's own ceiling (see AudioRules.gainSplit) and the
     * clip's voice. Written by the tick, so a slider or a voice chip is heard
     * at once with no player rebuilt.
     */
    private class AudioChain {
        val gain = AtomicReference(1f)
        val voice = AtomicReference(VoiceEffect.None)
    }

    /**
     * How fast one player is running, and when that was last changed.
     *
     * Every change restarts the audio time-stretcher, so how often it happens
     * decides whether a ramp sounds like a ramp or a rattle - see
     * [PreviewRules.shouldPushSpeed].
     */
    private class Rate {
        var speed: Float? = null
        var pitch: Float = Float.NaN
        var clipId: String? = null
        var pushedAt: Long = 0L
    }

    /** One video player and everything known about what it is showing. */
    private inner class Surface(val key: String, val isBase: Boolean) {
        /** The voice of the clip this surface is showing, read by its sink every buffer. */
        val voice = AtomicReference(VoiceEffect.None)
        // Every surface carries a voice: it is the clip's own now, an
        // overlay's as much as a shot's, and the export voices both.
        val player: ExoPlayer = newVideoPlayer { voice.get() }

        // Read by the shaders every frame; written by the tick.
        val chroma = AtomicReference<ChromaKey?>(null)
        val mask = AtomicReference<Pair<Mask?, Long>>(null to 0L)
        val background = AtomicReference<Pair<BackgroundRemoval?, Long>>(null to 0L)
        /**
         * The grade of the clip this surface is showing - its own look and
         * sliders, an overlay's as much as a shot's - read by the look shader
         * every frame. Changing it is a write here, never a pipeline rebuild;
         * see [LiveLookEffect].
         */
        val grade = AtomicReference(IDENTITY_GRADE)
        /** The clip [grade] was last worked out from, so a tick on the same clip costs a reference check. */
        var gradedClip: Clip? = null
        /** The clip, and the loupe state, the shader values were last written for; see [applyLive]. */
        var liveClip: Clip? = null
        var liveKeyPreview = true
        val effects = AtomicReference<List<TimedEffect>>(emptyList())

        /**
         * The blended still this surface draws under, in this surface's own
         * clock. One at a time: a second would need a second sampler and a
         * second branch in the shader, and nobody stacks two light leaks.
         */
        val blendStill = AtomicReference<BlendedStill?>(null)
        var effectsClip: Clip? = null
        var effectsFrom: List<TimedEffect>? = null

        /** False once the chain has failed and been taken off; see the error listener. */
        var effectsOn = false
        /** The chain this surface was built with, so a dropped one can go back on. */
        var chain: List<Effect> = emptyList()
        /** When the chain was last taken off; it is tried again after [CHAIN_RETRY_MS]. */
        var chainDroppedAt = 0L

        var loadedUri: String? = null
        /** The clip the player has been positioned for. */
        var activeClipId: String? = null
        /**
         * The clip whose frame is actually on the surface. Differs from
         * [activeClipId] from the moment the player is pointed at a new clip until
         * that clip's first frame has been drawn; the surface is not shown in
         * between, because what it holds is the last frame of some other shot.
         */
        var shownClipId: String? = null
        /**
         * The clip a first-frame callback arrived for while the player was still
         * busy with a seek; see [updateReadiness].
         */
        var firstFrameFor: String? = null
        var readyTicks = 0
        /** Set by a jump: the next sync seeks even though the clip has not changed. */
        var forceSeek = false
        /** A clip covers the playhead on this surface (rather than being parked ahead). */
        var covering = false
        var wasVisible = false
        var lastTransform: Transform = Transform.Identity
        var lastStabilizer: Transform = Transform.Identity
        /** The turn the last shot shown had, kept with its placement for the hold at a cut. */
        var lastMirrored = false
        var lastTurns = 0
        var lastCrop: ClipCrop? = null

        var lastWanted = 0L
        var wantedIn = 0L
        var wantedOut = 0L
        var lastCorrection = 0L
        var lastPosition = -1L

        val rate = Rate()
        val stall = StallWatch()

        var errorAt = 0L
        /** Failures close together; see [ERROR_MEMORY_MS]. */
        var recentErrors = 0

        /** The decoded picture's shape, from the player. */
        var videoAspect: Float? = null

        init {
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "player error on $key: ${error.errorCodeName}", error)
                    if (released) return
                    val at = SystemClock.elapsedRealtime()
                    recentErrors = if (at - errorAt < ERROR_MEMORY_MS) recentErrors + 1 else 1
                    // A shader that will not compile on this driver, or a pipeline
                    // that cannot reconfigure, fails here and not in the
                    // runCatching round setVideoEffects - asynchronously, on the
                    // player. The same chain will fail the same way, so it is taken
                    // off and the surface plays plain. Anything else (a decoder, a
                    // file, a timeout) is not the chain's fault and keeps it,
                    // unless the same surface fails twice in quick succession.
                    // Only the chain's own faults. Any two errors in a row used to count,
                    // so two decoder hiccups during a fast scroll took the mask, the
                    // look and the key off this surface for the rest of the session -
                    // seen on the phone as a Cut out mask that cut nothing.
                    val chainFault = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                    if (effectsOn && chainFault) {
                        effectsOn = false
                        chainDroppedAt = at
                        Log.w(TAG, "dropping effects on $key after ${error.errorCodeName}")
                        runCatching { player.setVideoEffects(emptyList()) }
                    }
                    // The player is idle now. Forgetting what it had makes the next
                    // sync load it again - after a pause that grows with each
                    // failure, so a file that cannot play is not reloaded thirty
                    // times a second.
                    loadedUri = null
                    activeClipId = null
                    // Nothing it holds is trustworthy now. Kept, the same clip id
                    // coming back on the next sync would count as drawn at once and
                    // show the stale frame of a player that is not playing.
                    shownClipId = null
                    firstFrameFor = null
                    errorAt = at
                }

                override fun onRenderedFirstFrame() {
                    val clip = activeClipId ?: return
                    // A seek masks the player's state to BUFFERING the moment it is
                    // asked for, and callbacks arrive in order on this thread. So a
                    // callback seen while READY belongs to the latest seek; one seen
                    // while BUFFERING may be left over from the seek before - the
                    // previous clip's picture - and only counts once the player
                    // has come back READY.
                    if (player.playbackState == Player.STATE_READY) shownClipId = clip else firstFrameFor = clip
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    PreviewBox.displayAspect(
                        videoSize.width, videoSize.height,
                        videoSize.unappliedRotationDegrees, videoSize.pixelWidthHeightRatio
                    )?.let { videoAspect = it }
                }
            })

            chain = buildList<Effect> {
                // Keyed first, then background, then masked - the export's order.
                add(ChromaKeyEffect { chroma.get() })
                add(BackgroundEffect({ background.get().first }, { background.get().second }, timesAreSourceTime = true))
                // The player holds the whole source file, so its clock is source time.
                add(MaskEffect({ mask.get().first }, { mask.get().second }, timesAreSourceTime = true))
                // Every picture has its own grade now, an overlay's as much as a
                // shot's; the export grades each item the same way. The effects
                // library still belongs to the whole picture, and only the base
                // carries it.
                add(LiveLookEffect(grade))
                if (isBase) {
                    // A blended still, through the file's own shader rather than
                    // a Compose layer: Compose cannot blend against a TextureView,
                    // and this way the preview and the export are the same program
                    // on the same numbers.
                    add(LiveLayerBlendEffect({ blendStill.get()?.bitmap }, { blendStill.get()?.mode ?: LayerBlend.Normal }) { atMs ->
                        blendStill.get()?.placementAt?.invoke(atMs)
                    })
                    // Writes alpha 1, so a base frame reaches its view opaque.
                    add(FxEffect({ effects.get() }, overBlack = true))
                } else {
                    // A layer keeps its transparency all the way to the screen,
                    // where its view blends it premultiplied; see PremultiplyEffect.
                    add(PremultiplyEffect())
                }
            }
            // Installed on an empty player, before anything is loaded: the order a
            // first load uses, and the only one that has always been reliable.
            effectsOn = runCatching { player.setVideoEffects(chain) }
                .onFailure { Log.w(TAG, "no effect chain on $key", it) }
                .isSuccess
        }
    }

    private val surfaceA = Surface(KEY_A, isBase = true)
    private val surfaceB = Surface(KEY_B, isBase = true)
    private val overlaySurfaces = LinkedHashMap<Int, Surface>()

    val baseA: ExoPlayer get() = surfaceA.player
    val baseB: ExoPlayer get() = surfaceB.player

    private val audioPlayers = LinkedHashMap<String, ExoPlayer>()
    private val audioSources = HashMap<String, String>()
    private val audioRates = HashMap<String, Rate>()
    private val audioChains = HashMap<String, AudioChain>()

    /** What each sound's placement was when it was last positioned; see [setTimeline]. */
    private var audioKeys: Map<String, String> = emptyMap()

    /** Sounds positioned since the last jump: each is seeked once, on the way in. */
    private val primed = HashSet<String>()

    /** The base track in timeline order, for finding the shot before a cut. */
    private var baseClips: List<Clip> = emptyList()
    private var rollA: List<Clip> = emptyList()
    private var rollB: List<Clip> = emptyList()
    private var rollAStarts: List<Long> = emptyList()
    private var rollBStarts: List<Long> = emptyList()
    private var overlayByLayer: Map<Int, List<Clip>> = emptyMap()
    private var overlayStarts: Map<Int, List<Long>> = emptyMap()
    private var layers: List<Int> = emptyList()
    private var audioClips: List<Clip> = emptyList()
    private var effects: List<TimedEffect> = emptyList()

    /** The blended stills of the edit, their pictures decoded once and kept. */
    private var blendStills: List<Clip> = emptyList()
    private val blendBitmaps = mutableMapOf<String, android.graphics.Bitmap?>()

    /**
     * One blended still as a base surface reads it: its picture, its mode, and
     * where it sits at a moment of *that surface's* clock, which is the shot's
     * source time.
     */
    class BlendedStill(
        val clipId: String,
        val underId: String,
        val bitmap: android.graphics.Bitmap?,
        val mode: LayerBlend,
        val placementAt: (Long) -> LayerBlendEffect.Placement?
    )

    private fun blendedStillFor(still: Clip, under: Clip): BlendedStill? {
        val uri = still.uri ?: return null
        val key = uri.toString()
        val bitmap = blendBitmaps.getOrPut(key) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        } ?: return null
        return BlendedStill(still.id, under.id, bitmap, still.blend) { sourceMs ->
            // Source time of the shot under it, back to the edit's own clock.
            val atMs = under.timelineAtSource(sourceMs)
            if (atMs < still.timelineStartMs || atMs > still.timelineEndMs) null
            else LayerBlendEffect.Placement(
                lookup = LayerBlendEffect.WHOLE_FRAME,
                alpha = still.opacityAt(atMs).coerceIn(0f, 1f)
            )
        }
    }

    private var fallbackUri: Uri? = null
    /** Each heavy file's light stand-in, by the file. */
    private var proxies: Map<Uri, Uri> = emptyMap()

    /**
     * Proxies that finished while the transport was running. Swapping to one
     * reloads the surface on screen - a hitch in the middle of playback, for a
     * file that looks the same - so it waits for a pause or a jump, either of
     * which interrupts the picture anyway.
     */
    private var pendingProxies: Map<Uri, Uri> = emptyMap()
    private var proxyPending = false

    /** The camera sound for the whole edit, which every main-track shot is heard under. */
    private var muteOriginal = false
    private var originalVolume = 1f

    private var clockClipId: String? = null
    private var clockKey: String = KEY_A

    private var positionMs: Long = 0
    private var durationMs: Long = 0
    private var playing: Boolean = false
    private var inGap: Boolean = false
    private var pictureEnded: Boolean = false

    /** Set when what a paused frame shows has changed; serviced by the tick. */
    private var pendingRedraw = false
    private var lastRedrawAt = 0L

    private val scrub = ScrubDetector()
    /** Told by the timeline when a finger is on the ruler; see [setScrubbing]. */
    private var scrubbingHeld = false
    private var videoSeek: SeekParameters = SeekParameters.EXACT
    /**
     * Some surface may be sitting on a sync sample rather than the frame asked
     * for: seeks have been served CLOSEST_SYNC since the last exact settle.
     */
    private var sloppy = false
    /** The finger has lifted; the next tick settles the scrub. */
    private var settlePending = false

    /**
     * The shot a hard cut is holding the outgoing picture for, and since when;
     * see [holdsCut].
     */
    private var holdFor: String? = null
    private var holdSince = 0L
    /** The base time the last tick composed, or -1 after a jump. */
    private var lastBaseAt = -1L

    private var anchorTimelineMs: Long = 0
    private var anchorWallMs: Long = SystemClock.elapsedRealtime()

    private val scrubbing: Boolean get() = scrubbingHeld || scrub.inFlight

    /**
     * Holds every level at nothing while [on] - a take is being recorded - and
     * lets them back the moment it lifts. The levels are set every tick, so
     * this is one flag they all read rather than a pass over the players.
     */
    fun setMuted(on: Boolean) {
        muted = on
    }

    /**
     * Draws every clip's green screen (true), or none (false) while a key
     * colour is being picked off the picture; see [keyPreview]. Applied on
     * the next tick, with a fresh frame asked for.
     */
    fun setKeyPreview(on: Boolean) {
        if (released || on == keyPreview) return
        keyPreview = on
        requestRedraw()
    }

    /**
     * Renderers whose audio passes through the given processors - a
     * [VoiceProcessor] reading the clip's voice, and for a sound player a
     * [GainProcessor] before it - so robot, echo, radio and a boost past full
     * are heard in the preview and change without the player being rebuilt.
     */
    private fun processedRenderers(vararg processors: AudioProcessor) = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(*processors))
            .build()
    }

    private fun newVideoPlayer(voiceNow: () -> VoiceEffect) =
        ExoPlayer.Builder(context, processedRenderers(VoiceProcessor(voiceNow)))
        // Ready on half a second of buffer rather than the default two and a half.
        // A shot parked ahead of a cut has to be ready before the cut, and on a
        // heavy original the default was a large part of the stall at every one.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 5_000,
                    /* maxBufferMs = */ 20_000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_000
                )
                .build()
        )
        // Letting go of a surface waits on the playback thread. If that thread is
        // stuck, the main thread waits with it - two seconds by default, which on
        // the device was two seconds of an editor ignoring every tap, repeated.
        .setDetachSurfaceTimeoutMs(DETACH_TIMEOUT_MS)
        .build()
        .apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }

    /**
     * A player for an added sound, built differently from a video surface on
     * purpose. Three settings, and each is the reason for something that went wrong:
     *
     * - **CLOSEST_SYNC rather than EXACT.** An exact seek into a compressed audio
     *   stream decodes and throws away everything from the previous sync sample to
     *   land on the right frame. On a cold file that work lands in the same moment
     *   playback is meant to start. Music does not need frame accuracy - a few
     *   milliseconds is inaudible, and the offset controls exist for the rest.
     * - **A short buffer-for-playback.** The default waits for 2.5 seconds of audio
     *   before it will start. Starting from a quarter second means the decoder is
     *   ahead of the playhead by the time the cue is due rather than racing it.
     * - **No video track.** A video file picked for its sound would otherwise
     *   decode its pictures into nowhere, holding a hardware decoder the surfaces
     *   need.
     */
    private fun newAudioPlayer(chain: AudioChain) = ExoPlayer.Builder(
        context,
        // The boost first, so a voice hears the level it will be written at.
        processedRenderers(GainProcessor { chain.gain.get() }, VoiceProcessor { chain.voice.get() })
    )
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
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

    private fun overlaySurface(layer: Int): Surface = overlaySurfaces.getOrPut(layer) {
        // Silent until a clip gives it a level; see syncSurface.
        Surface(KEY_OVERLAY + layer, isBase = false).also {
            it.player.volume = 0f
            it.player.setSeekParameters(videoSeek)
        }
    }

    /** The player for an overlay layer, created the first time that layer appears. */
    fun overlayPlayer(layer: Int): ExoPlayer = overlaySurface(layer).player

    private fun allSurfaces(): List<Surface> = listOf(surfaceA, surfaceB) + overlaySurfaces.values

    // ---- Timeline ---------------------------------------------------------------

    /**
     * Hands the engine the edit as it now stands.
     *
     * Cheap on purpose, and called on every change: a slider tick on an overlay's
     * size or a sound's level lands here thirty times a second. It used to
     * re-seek every sound each time, which during playback is a decoder flush per
     * frame of the drag - audio breaking up for as long as a finger was on a
     * slider. Now only a sound whose placement actually moved is positioned again.
     */
    fun setTimeline(
        videoClips: List<Clip>,
        audioClips: List<Clip>,
        effects: List<TimedEffect>,
        fallbackUri: Uri,
        proxies: Map<Uri, Uri>,
        muteOriginal: Boolean,
        originalVolume: Float
    ) {
        if (released) return
        // A paused picture does not redraw by itself, so a new effect would not
        // show until play. The next tick asks for a fresh frame. A clip's own
        // grade, mask or key is noticed per surface, in applyLive.
        if (effects != this.effects) requestRedraw()
        // Blended stills are not surfaces of their own - they are drawn into the
        // shot under them, as the file draws them.
        val stills = videoClips.filter { it.isOverlay && !it.blend.isPlain && StillClips.isStill(it.uri) }
        if (stills != blendStills) {
            blendStills = stills
            blendBitmaps.keys.retainAll(stills.mapNotNull { it.uri?.toString() }.toSet())
            requestRedraw()
        }
        this.effects = effects
        val base = videoClips.filter { !it.isOverlay }.sortedBy { it.timelineStartMs }

        // Index parity, exactly as CompositionFactory deals the export's two rolls.
        // Matching it is not an aesthetic choice: if preview and export disagreed
        // about which shot is on which roll, a dissolve would preview one way and
        // render the other.
        baseClips = base
        rollA = base.filterIndexed { i, _ -> i % 2 == 0 }
        rollB = base.filterIndexed { i, _ -> i % 2 == 1 }
        rollAStarts = rollA.map { it.timelineStartMs }
        rollBStarts = rollB.map { it.timelineStartMs }

        // Footage only. A photo on a row is drawn by the preview itself (see
        // TimelinePreview) and needs no player - and no decoder.
        overlayByLayer = videoClips.filter { it.isOverlay && !StillClips.isStill(it.uri) }
            .groupBy { it.layer }
            .mapValues { (_, clips) -> clips.sortedBy { it.timelineStartMs } }
        overlayStarts = overlayByLayer.mapValues { (_, clips) -> clips.map { it.timelineStartMs } }
        layers = overlayByLayer.keys.sorted()

        // A layer that has been deleted or dropped back onto the base track is no
        // longer walked by syncOverlays, so nothing would ever tell its player to
        // stop - it would keep playing, unseen and unheard but decoding.
        overlaySurfaces.forEach { (layer, s) ->
            if (layer in layers) return@forEach
            if (s.player.playWhenReady) s.player.pause()
            unload(s)
        }

        this.audioClips = audioClips
        this.fallbackUri = fallbackUri
        if (playing && proxies != this.proxies) {
            pendingProxies = proxies
            proxyPending = true
        } else {
            this.proxies = proxies
            proxyPending = false
        }

        // Each surface's level is set from the clip it is showing, every tick
        // (see syncSurface): a shot's own under these, an overlay's alone.
        this.muteOriginal = muteOriginal
        this.originalVolume = originalVolume

        durationMs = maxOf(
            videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
        )

        // Level is not part of the key: it is applied every tick anyway.
        val keys = audioClips.associate { clip ->
            clip.id to "${clip.uri}@${clip.timelineStartMs}:${clip.sourceInMs}-${clip.sourceOutMs}:${clip.speedRamp}"
        }
        if (keys != audioKeys) {
            reconcileAudioPlayers()
            val moved = keys.filter { (id, key) -> audioKeys[id] != key }.keys
            audioKeys = keys
            primed.removeAll(moved)
            // Parked where the playhead already is, so the decoder is warm and on
            // the right sample before anyone presses play. While playing, the tick
            // positions a moved sound once on its own.
            if (!playing && !scrubbing) primeAudio(positionMs, moved)
        }
    }

    private fun requestRedraw() {
        if (!playing) pendingRedraw = true
    }

    private fun applyPendingProxy() {
        if (!proxyPending) return
        proxies = pendingProxies
        proxyPending = false
    }

    /**
     * Puts one player at the rate its clip calls for, if that has moved enough to
     * be worth the interruption.
     */
    /**
     * The pitch a player runs at for [clip] at [speed]: its voice's shift, and
     * the speed's own on top when the clip lets the pitch follow the speed -
     * the same choice the export makes (VideoProcessor.buildAudioProcessors).
     * Held to what the player is actually asked for (PreviewRules.playerRate),
     * since the sink resamples by it and holds pitch to the same limits.
     */
    private fun pitchFor(clip: Clip, speed: Float): Float =
        if (clip.pitchFollowsSpeed) PreviewRules.playerRate(clip.voice.pitch * PreviewRules.playerRate(speed))
        else clip.voice.pitch

    private fun setSpeed(player: ExoPlayer, rate: Rate, clipId: String, wanted: Float, pitch: Float) {
        val safe = PreviewRules.playerRate(wanted)
        val now = SystemClock.elapsedRealtime()
        val push = PreviewRules.shouldPushSpeed(rate.speed, safe, now - rate.pushedAt, rate.clipId != clipId) ||
            rate.pitch != pitch
        if (!push) return
        rate.speed = safe
        rate.pitch = pitch
        rate.clipId = clipId
        rate.pushedAt = now
        runCatching { player.playbackParameters = PlaybackParameters(safe, pitch) }
    }

    /**
     * Hands one surface's shaders the settings of the clip it is on.
     *
     * Values, not a chain: see the class comment. When the surface is on screen
     * and paused, a change asks for a fresh frame, since a paused pipeline draws
     * nothing new by itself.
     */
    private fun applyLive(s: Surface, clip: Clip, timelineMs: Long) {
        // Only when something read here has changed: an edit makes a new Clip,
        // the loupe flips keyPreview, the library makes a new effects list.
        // This runs per surface per tick, and the pairs and lists built below
        // to find "nothing changed" were the churn the playback audit named.
        // A keyed filter strength is read every tick: its grade is a function of
        // where the playhead is, so "the clip has not changed" is not enough.
        if (!clip.lookAnimated &&
            clip === s.liveClip && keyPreview == s.liveKeyPreview && (!s.isBase || effects === s.effectsFrom)
        ) return
        s.liveClip = clip
        s.liveKeyPreview = keyPreview
        var changed = false
        val chroma = if (keyPreview) clip.chromaKey else null
        if (s.chroma.get() != chroma) {
            s.chroma.set(chroma)
            changed = true
        }
        // Worked out again only when the clip changed - an edit makes a new
        // Clip - and written only when it came out different.
        if (clip !== s.gradedClip || clip.lookAnimated) {
            s.gradedClip = clip
            val grade = if (clip.lookAnimated) clip.gradeAt(timelineMs - clip.timelineStartMs) else clip.grade
            if (grade != s.grade.get()) {
                s.grade.set(grade)
                changed = true
            }
        }
        if (s.isBase) {
            // The still that covers this shot, if any, with its placement read
            // in this surface's clock. The player holds the whole source file,
            // so that clock is source time: source -> timeline through the
            // clip's own trim and speed, the way the effects library is mapped
            // a few lines down.
            val still = blendStills.firstOrNull {
                it.timelineEndMs > clip.timelineStartMs && it.timelineStartMs < clip.timelineEndMs
            }
            val was = s.blendStill.get()
            if (was?.clipId != still?.id || was?.underId != clip.id) {
                s.blendStill.set(still?.let { blendedStillFor(it, clip) })
                changed = true
            }
        }
        val mask = clip.mask to clip.sourceInMs
        if (s.mask.get() != mask) {
            s.mask.set(mask)
            changed = true
        }
        val background = clip.background to clip.sourceInMs
        if (s.background.get() != background) {
            s.background.set(background)
            changed = true
        }
        // The effects library, in this clip's own clock. Only worked out again
        // when the clip or the list changed, not per tick.
        if (s.isBase && (clip !== s.effectsClip || effects !== s.effectsFrom)) {
            s.effectsClip = clip
            s.effectsFrom = effects
            // Drawn over the whole canvas instead where the phone can
            // (CanvasFx): the chain's pass is then left at rest, a copy that
            // still writes the frame opaque, so the chain is never rebuilt.
            val here = if (CanvasFx.enabled) emptyList() else effects
                .filter { it.endMs > clip.timelineStartMs && it.startMs < clip.timelineEndMs }
                .map { it.shiftedInto(clip) }
            if (here != s.effects.get()) {
                s.effects.set(here)
                changed = true
            }
        }
        if (changed && s.covering) requestRedraw()
    }

    // ---- Transport --------------------------------------------------------------

    fun play() {
        if (released) return
        if (durationMs > 0 && positionMs >= durationMs) seekTo(0)
        // Play from a scrub still settling: the surfaces may be on sync samples,
        // and the clock is about to be read off one of them. Land them exactly
        // first, or playback carries on from the keyframe instead of the playhead.
        if (sloppy || scrub.inFlight || settlePending) settle()
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
        allSurfaces().forEach { it.player.pause() }
        audioPlayers.values.forEach { it.pause() }
        applyPendingProxy()
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
        val inScrub = scrub.onSeek(SystemClock.elapsedRealtime()) || scrubbingHeld
        // Sync samples only while paused. Playing, the clock is read off a
        // player's position, so a player landed on a keyframe up to a GOP away
        // would carry the playhead there and the place the user asked for would be
        // lost; an exact seek costs a moment's buffering, and the clock holds
        // through that.
        useVideoSeek(if (inScrub && !playing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        // Every surface re-resolves its clip and seeks on the next tick, which is
        // what makes a jump land on the right frame of the right shot on every
        // layer at once.
        allSurfaces().forEach { it.forceSeek = true }
        clockClipId = null
        // A jump is not a cut: nothing on screen is the shot before this one.
        lastBaseAt = -1L
        holdFor = null
        applyPendingProxy()
        // Sounds are positioned once the scrub is over, not on every pointer event.
        if (!inScrub) primeAudio(positionMs)
    }

    /**
     * A finger is on the timeline (true) or has just left it (false). Optional:
     * seeks arriving close together are recognised as a scrub without it; this
     * ends one the moment the finger lifts rather than a moment later, and keeps
     * a finger that rests mid-drag from being taken for the end of it.
     */
    fun setScrubbing(on: Boolean) {
        if (released || on == scrubbingHeld) return
        scrubbingHeld = on
        if (on) {
            if (!playing) useVideoSeek(SeekParameters.CLOSEST_SYNC)
        } else {
            // Settled on the next tick even if the drag made no quick seeks at all:
            // one drag event is still a sync-sample seek that has to be made exact.
            scrub.end()
            settlePending = true
        }
    }

    /**
     * The scrub is over: the frame it stopped on, exactly, and the sounds
     * positioned there - once.
     */
    private fun settle() {
        scrub.reset()
        settlePending = false
        useVideoSeek(SeekParameters.EXACT)
        // Only surfaces that may be on a sync sample are sought again. A scrub
        // made while playing was exact throughout, and seeking a running player
        // onto its own position is a rebuffer for nothing.
        if (sloppy) allSurfaces().forEach { it.forceSeek = true }
        sloppy = false
        primeAudio(positionMs)
    }

    private fun useVideoSeek(params: SeekParameters) {
        if (params != SeekParameters.EXACT) sloppy = true
        if (params == videoSeek) return
        videoSeek = params
        allSurfaces().forEach { it.player.setSeekParameters(params) }
    }

    /**
     * Puts the current frame back on screen after the picture changed size, or
     * after something the paused frame shows changed.
     *
     * A paused TextureView keeps the frame it had, at the size it had it, and a
     * paused pipeline draws no new frame when a shader's value changes. A seek
     * makes the decoder hand over a fresh one - to the frame the playhead is on,
     * worked out from the clip every time; see [PreviewRules.redrawTarget].
     */
    fun redraw() {
        if (released || playing) return
        allSurfaces().forEach { s ->
            if (s.loadedUri == null || s.activeClipId == null || !s.covering) return@forEach
            s.player.seekTo(PreviewRules.redrawTarget(s.lastWanted, s.player.currentPosition, s.wantedIn, s.wantedOut))
        }
    }

    /**
     * Parks sounds on the sample they will need, without starting them - every
     * sound, or only those in [only].
     *
     * Called on load, on seek and on play rather than at the moment a cue is due,
     * because a seek is only free when nothing is waiting on it. This is the whole
     * fix for music breaking up on the first few plays and settling down later: it
     * settled because the file had become warm, so the answer is to warm it
     * deliberately instead of hoping.
     */
    private fun primeAudio(t: Long, only: Set<String>? = null) {
        audioClips.forEach { clip ->
            if (only != null && clip.id !in only) return@forEach
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

        // A scrub has gone quiet, or the finger has lifted. Never while a finger
        // is still down, however long it rests.
        if (!scrubbingHeld && (settlePending || scrub.settled(now))) settle()

        updateReadiness()

        val clock = if (clockKey == KEY_A) surfaceA else surfaceB
        val clockPlayer = clock.player
        // Searched in place: joining the rolls built a list thirty times a second.
        val clockClip = rollA.firstOrNull { it.id == clockClipId } ?: rollB.firstOrNull { it.id == clockClipId }
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

        var reachedEnd = false
        if (playing && durationMs > 0 && t >= durationMs) {
            t = durationMs
            pause()
            reachedEnd = true
        }

        positionMs = t
        anchorTimelineMs = t
        anchorWallMs = now

        // Played through to the end: the players ran on past the out point before
        // the tick noticed, so the picture they are paused on is a frame the trim
        // hides. Put them back on the clip's own last frame.
        if (reachedEnd) {
            surfaceA.forceSeek = true
            surfaceB.forceSeek = true
        }
        val (drawA, drawB) = composeBase(t, now)
        // Parked on the very end, a layer or caption that runs to it is on the
        // last frame, as in the file - not gone because nothing covers the end.
        val overlays = syncOverlays(PreviewRules.lastFrameTime(t, durationMs))
        watchStalls(now)

        if (pendingRedraw && !playing && now - lastRedrawAt >= REDRAW_GAP_MS) {
            pendingRedraw = false
            lastRedrawAt = now
            redraw()
        }
        syncAudio(t, transportRunning = playing && !stalled)

        return PreviewFrame(
            positionMs = t,
            durationMs = durationMs,
            isPlaying = playing,
            inGap = inGap,
            pictureEnded = pictureEnded,
            surfaceA = drawA,
            surfaceB = drawB,
            overlays = overlays
        )
    }

    /**
     * Notices when a surface pointed at a new clip has drawn it.
     *
     * The first-frame callback is the fast way; a player that has sat READY for
     * two ticks since the switch is the fallback, because a gate that never opens
     * would be a picture that never appears - far worse than a stale frame.
     *
     * A callback that came while a seek was still in flight counts only once the
     * player is READY again: by then that seek has landed and its frame is up.
     */
    private fun updateReadiness() {
        allSurfaces().forEach { s ->
            val ready = s.player.playbackState == Player.STATE_READY
            if (s.activeClipId == null || s.shownClipId == s.activeClipId) return@forEach
            if (ready) {
                s.readyTicks++
                if (s.readyTicks >= READY_TICKS || s.firstFrameFor == s.activeClipId) s.shownClipId = s.activeClipId
            } else {
                s.readyTicks = 0
            }
        }
    }

    // ---- Base track and transitions ---------------------------------------------

    private fun composeBase(t: Long, now: Long): Pair<SurfaceDraw, SurfaceDraw> {
        // A clip covers up to, not including, its end - so parked exactly on the
        // end of the edit, where every play-through stops, nothing covered it and
        // the picture went to "Gap". The end shows the last frame instead - when
        // the shots run to it; past them, with a layer or a sound still going,
        // the picture has ended and the clock runs on (PreviewRules.baseTime).
        val end = maxOf(rollA.lastOrNull()?.timelineEndMs ?: 0L, rollB.lastOrNull()?.timelineEndMs ?: 0L)
        val at = PreviewRules.baseTime(t, end, durationMs)
        val clipA = rollA.lastOrNull { covers(it, at) }
        val clipB = rollB.lastOrNull { covers(it, at) }

        // A hard cut whose incoming shot has not drawn yet holds the outgoing one
        // on screen, paused on its last frame, rather than showing whatever the
        // incoming surface drew last - or black. That surface is not parked on
        // anything new while it is being held, or it would change under the eye.
        val holdA = clipA == null && clipB != null && holdsCut(clipB, surfaceB, surfaceA, now)
        val holdB = clipB == null && clipA != null && holdsCut(clipA, surfaceA, surfaceB, now)
        if (!holdA && !holdB) holdFor = null
        lastBaseAt = at

        driveRoll(surfaceA, rollA, rollAStarts, clipA, at, lookahead = !holdA)
        driveRoll(surfaceB, rollB, rollBStarts, clipB, at, lookahead = !holdB)

        if (clipA == null && clipB == null) {
            inGap = rollA.isNotEmpty() || rollB.isNotEmpty()
            pictureEnded = inGap && at >= end
            // No shot drives the clock here: it runs on wall time. Left on the
            // shot that just ended, a park or a redraw seek on that player read
            // as a stall and held the clock still in the middle of a gap.
            clockClipId = null
            return remember(SurfaceDraw(), SurfaceDraw())
        }
        inGap = false
        pictureEnded = false

        // Whichever shot was already driving keeps the clock for the whole
        // transition. Handing it over mid-blend would step the playhead by whatever
        // the two players happen to differ by.
        val stillClocking = listOfNotNull(clipA, clipB).any { it.id == clockClipId }
        if (!stillClocking) {
            val next = listOfNotNull(clipA, clipB).minByOrNull { it.timelineStartMs }
            clockClipId = next?.id
        }
        // Which surface that shot is on, asked every pass rather than only when
        // the clock changes hands: an edit made while playing (an earlier shot
        // deleted, floated, or the step undone) re-deals the rolls by parity,
        // and the shot still covering moved to the other surface while the
        // clock went on reading the one it had left - a stall, a drift chased
        // by resync seeks, or a time off another shot's position.
        clockKey = if (PreviewRules.clockOnA(clipA?.id, clockClipId)) KEY_A else KEY_B

        val readyA = clipA != null && surfaceA.shownClipId == clipA.id
        val readyB = clipB != null && surfaceB.shownClipId == clipB.id

        if (clipA == null || clipB == null) {
            val onA = clipA != null
            val only = (clipA ?: clipB)!!
            val ready = if (onA) readyA else readyB
            // Its own fade - a keyed opacity, an arrival - as the file draws it.
            val own = if (onA) surfaceA else surfaceB
            val shown = SurfaceDraw(visible = ready, alpha = only.opacityAt(at), transform = only.placedAt(at), stabilizer = only.stabilizerAt(at)).turnedAs(only, own)
            val held = if (onA) surfaceB else surfaceA
            val other = if (!ready && (if (onA) holdB else holdA)) {
                SurfaceDraw(
                    visible = true, transform = held.lastTransform, stabilizer = held.lastStabilizer,
                    mirrored = held.lastMirrored, quarterTurns = held.lastTurns, crop = held.lastCrop,
                    clipId = held.shownClipId, aspect = held.videoAspect
                )
            } else {
                SurfaceDraw(visible = false, transform = only.placedAt(at), stabilizer = only.stabilizerAt(at), crop = only.crop, clipId = only.id)
            }
            return if (onA) remember(shown, other) else remember(other, shown)
        }

        // Both rolls have a shot here, so the two overlap: a transition.
        val incoming = if (clipA.timelineStartMs >= clipB.timelineStartMs) clipA else clipB
        val outgoing = if (incoming === clipA) clipB else clipA
        val overlapMs = (outgoing.timelineEndMs - incoming.timelineStartMs).coerceAtLeast(1L)
        val progress = ((at - incoming.timelineStartMs).toFloat() / overlapMs).coerceIn(0f, 1f)

        val (outDraw, inDraw) = blend(incoming.transitionIn.type, progress)
        val aIsIncoming = incoming === clipA
        val inReady = if (aIsIncoming) readyA else readyB
        val outReady = if (aIsIncoming) readyB else readyA
        // Both shots keep animating through the blend, which is the point of
        // keyframing a transition - a push-in that stalls mid-dissolve is a glitch.
        // A shot not yet decoded sits the blend out rather than blending in a
        // stale frame. Each shot's own fade multiplies its share of the blend,
        // as the export's TransitionEffect does.
        val outMoved = outDraw.copy(
            visible = outDraw.visible && outReady,
            alpha = outDraw.alpha * outgoing.opacityAt(at),
            transform = outgoing.placedAt(at), stabilizer = outgoing.stabilizerAt(at)
        ).turnedAs(outgoing, if (aIsIncoming) surfaceB else surfaceA)
        val inMoved = inDraw.copy(
            visible = inDraw.visible && inReady,
            alpha = inDraw.alpha * incoming.opacityAt(at),
            transform = incoming.placedAt(at), stabilizer = incoming.stabilizerAt(at)
        ).turnedAs(incoming, if (aIsIncoming) surfaceA else surfaceB)
        return remember(
            if (aIsIncoming) inMoved else outMoved,
            if (aIsIncoming) outMoved else inMoved
        )
    }

    /**
     * Whether the cut into [incoming] should keep the outgoing picture up on
     * [held] while [inSurface] finishes getting the new shot ready; see
     * [PreviewRules.holdAtCut] for the rule.
     *
     * Only at a cut the playhead played into, and only when [held] is showing
     * the shot that ends there. A jump arrives with the held surface showing
     * some other part of the edit, and holding that over the new time was a
     * frame from the wrong shot for as long as the seek took.
     */
    private fun holdsCut(incoming: Clip, inSurface: Surface, held: Surface, now: Long): Boolean {
        if (inSurface.shownClipId == incoming.id) return false
        val i = baseClips.indexOfFirst { it.id == incoming.id }
        val before = baseClips.getOrNull(i - 1) ?: return false
        val continuing = holdFor == incoming.id
        val hold = PreviewRules.holdAtCut(
            heldShowsOutgoing = held.shownClipId == before.id && before.timelineEndMs >= incoming.timelineStartMs,
            arrivedByPlaying = continuing || (held.wasVisible && lastBaseAt in 0 until incoming.timelineStartMs),
            heldForMs = if (continuing) now - holdSince else 0L,
            incomingFailed = inSurface.player.playerError != null
        )
        if (hold && !continuing) {
            holdFor = incoming.id
            holdSince = now
            // The outgoing player's own clock is what told the tick the cut had
            // come, so by now it has drawn up to a tick past the out point - the
            // frames the trim hides. Held, it is put back on the shot's own last
            // frame, the way the end of the edit is.
            runCatching {
                held.player.seekTo(
                    PreviewRules.seekTarget(before.sourceAt(before.timelineEndMs - 1), before.sourceInMs, before.sourceOutMs)
                )
            }
        }
        return hold
    }

    /**
     * Notes what each base surface showed - its placement, turn and crop, for
     * the hold at the next hard cut. Each draw carries its surface's picture
     * shape already (turnedAs), a held one included.
     */
    private fun remember(a: SurfaceDraw, b: SurfaceDraw): Pair<SurfaceDraw, SurfaceDraw> {
        surfaceA.wasVisible = a.visible
        surfaceB.wasVisible = b.visible
        if (a.visible) surfaceA.noteShown(a)
        if (b.visible) surfaceB.noteShown(b)
        return a to b
    }

    private fun Surface.noteShown(draw: SurfaceDraw) {
        lastTransform = draw.transform
        lastStabilizer = draw.stabilizer
        lastMirrored = draw.mirrored
        lastTurns = draw.quarterTurns
        lastCrop = draw.crop
    }

    /** The draw with [clip]'s own mirror, turn and crop, which clip it is, and the shape [s] has decoded. */
    private fun SurfaceDraw.turnedAs(clip: Clip, s: Surface): SurfaceDraw =
        copy(mirrored = clip.mirrored, quarterTurns = clip.quarterTurns, crop = clip.crop, clipId = clip.id, aspect = s.videoAspect ?: fileAspect(clip.uri))

    /**
     * A file's picture shape as seen (rotation tag applied), read once off the
     * file. A player with an effect chain reports its video size as 0x0 on this
     * phone, so [Surface.videoAspect] stayed unset and every shot was taken to be
     * the edit's own shape: a portrait photo in a 4:3 edit had its Crop window
     * drawn over the black bars beside it, and a crop was cut from the canvas
     * rather than the picture the export cuts it from. Null until read.
     */
    private fun fileAspect(uri: android.net.Uri?): Float? {
        val key = uri?.toString() ?: return null
        fileAspects[key]?.let { return it }
        if (aspectsAsked.add(key)) {
            Thread {
                val meta = runCatching { kotlinx.coroutines.runBlocking { com.squish.app.media.ThumbnailExtractor.probe(context, uri) } }.getOrNull()
                if (meta != null && meta.displayWidth > 0 && meta.displayHeight > 0) {
                    fileAspects[key] = meta.displayWidth.toFloat() / meta.displayHeight
                }
            }.apply { name = "squish-aspect"; isDaemon = true }.start()
        }
        return null
    }

    private val fileAspects = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val aspectsAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Gives one roll's player its work for this tick: the clip under the playhead
     * if there is one; otherwise the next clip on this roll, parked on its first
     * frame ahead of time; otherwise nothing.
     *
     * The parking is the lookahead the audio already had and the video never
     * did. Without it the incoming player was only opened at the instant of the
     * cut, so every cut began with a cold load and seek: the clock held, and the
     * surface showed whatever it had drawn last, or black, until the seek landed.
     */
    private fun driveRoll(s: Surface, roll: List<Clip>, starts: List<Long>, covering: Clip?, at: Long, lookahead: Boolean) {
        s.covering = covering != null
        if (covering != null) {
            syncSurface(s, covering, at, park = false)
            return
        }
        if (lookahead && !scrubbing) {
            val i = PreviewRules.upcoming(starts, at, LOOKAHEAD_MS)
            if (i >= 0) {
                val next = roll[i]
                syncSurface(s, next, next.timelineStartMs, park = true)
                return
            }
        }
        syncSurface(s, null, at, park = false)
    }

    /**
     * How a transition reads, as (outgoing, incoming): the export's own
     * decision (ExportPlan.blend) with the incoming shot on top, which is how
     * the screen stacks the two surfaces. It used to be written here a second
     * time, and every new kind would have had to be written twice and kept the
     * same by hand; the ExportPlanChecks hold the file to this stacking.
     */
    private fun blend(type: TransitionType, p: Float): Pair<SurfaceDraw, SurfaceDraw> {
        val (incoming, outgoing) = ExportPlan.blend(type, p, incomingOnTop = true)
        return SurfaceDraw.of(outgoing, onTop = false) to SurfaceDraw.of(incoming, onTop = true)
    }

    // ---- Overlay layers ----------------------------------------------------------

    private fun syncOverlays(t: Long): List<OverlayPlacement> = layers.map { layer ->
        val clips = overlayByLayer[layer].orEmpty()
        val clip = clips.lastOrNull { covers(it, t) }
        val s = overlaySurface(layer)
        s.covering = clip != null
        // The same lookahead as the base rolls: a layer coming up is opened and
        // parked on its first frame before it is due.
        val next = if (clip == null && !scrubbing) {
            clips.getOrNull(PreviewRules.upcoming(overlayStarts[layer].orEmpty(), t, LOOKAHEAD_MS))
        } else null
        when {
            clip != null -> syncSurface(s, clip, t, park = false)
            next != null -> syncSurface(s, next, next.timelineStartMs, park = true)
            else -> {
                syncSurface(s, null, t, park = false)
                // Nothing on this row anywhere near: its decoder goes back. Not
                // mid-scrub, where the playhead may swing straight back.
                if (!scrubbing && clips.none { near(it, t) }) unload(s)
            }
        }

        if (clip == null) {
            OverlayPlacement(layer = layer, visible = false, aspect = s.videoAspect)
        } else {
            // Its opacity track and fade, and a transition over its head, as
            // the export draws them (TransitionEffect on the overlay's chain).
            val own = ExportPlan.ownDrawAt(clip, t - clip.timelineStartMs)
            OverlayPlacement(
                layer = layer,
                visible = s.shownClipId == clip.id,
                opacity = own.alpha,
                transform = clip.placedAt(t),
                stabilizer = clip.stabilizerAt(t),
                mirrored = clip.mirrored,
                quarterTurns = clip.quarterTurns,
                draw = own,
                aspect = s.videoAspect ?: fileAspect(clip.uri),
                covers = true,
                clipId = clip.id,
                crop = clip.crop
            )
        }
    }

    /** Whether [clip] is within [IDLE_RELEASE_MS] of [t], either side. */
    private fun near(clip: Clip, t: Long): Boolean =
        t >= clip.timelineStartMs - IDLE_RELEASE_MS && t < clip.timelineEndMs + IDLE_RELEASE_MS

    // ---- Shared surface plumbing --------------------------------------------------

    /**
     * Points one player at a clip, or parks it.
     *
     * [park] positions the player on the clip's first frame and leaves it paused:
     * the lookahead, getting the next shot ready while the current one plays.
     */
    private fun syncSurface(s: Surface, clip: Clip?, t: Long, park: Boolean) {
        val player = s.player
        if (clip == null) {
            if (player.playWhenReady) player.pause()
            s.activeClipId = null
            return
        }

        val source = playbackUriFor(clip) ?: return
        applyLive(s, clip, t)
        // The clip's own voice: the processed ones through the sink, a pitch
        // shift as a playback parameter - and the speed's own shift with it
        // when the clip asks for that, as the file writes it.
        s.voice.set(clip.voice)
        val speed = clip.speedAt(if (park) clip.timelineStartMs else t)
        setSpeed(player, s.rate, clip.id, speed, pitch = pitchFor(clip, speed))
        val wanted = PreviewRules.seekTarget(clip.sourceAt(t), clip.sourceInMs, clip.sourceOutMs)
        s.lastWanted = wanted
        s.wantedIn = clip.sourceInMs
        s.wantedOut = clip.sourceOutMs
        val now = SystemClock.elapsedRealtime()

        when {
            s.loadedUri != source.toString() -> {
                if (now - s.errorAt < retryDelayMs(s.recentErrors)) {
                    if (player.playWhenReady) player.pause()
                    return
                }
                // A chain taken off after a fault goes back on with the next load, once
                // it has had time to be something passing (a driver hiccup, a file):
                // off for good, every mask, look and key on the surface stopped showing.
                if (!s.effectsOn && s.chain.isNotEmpty() && now - s.chainDroppedAt >= CHAIN_RETRY_MS) {
                    s.effectsOn = runCatching { player.setVideoEffects(s.chain) }.isSuccess
                    if (s.effectsOn) Log.i(TAG, "effects back on ${s.key}")
                }
                player.setMediaItem(MediaItem.fromUri(source))
                player.prepare()
                s.loadedUri = source.toString()
                switchTo(s, clip.id)
                player.seekTo(wanted)
            }
            // A different slice of the same file - a split, or a jump. No reload:
            // the media is already open, so this is a seek and nothing more.
            s.activeClipId != clip.id -> {
                switchTo(s, clip.id)
                player.seekTo(wanted)
            }
            s.forceSeek -> {
                s.forceSeek = false
                player.seekTo(wanted)
            }
            // Zero by construction while this surface drives the clock, so playback
            // is never interrupted by its own correction.
            //
            // Only while the player is in a state where its position means
            // something, and never twice in quick succession: a seek is not free,
            // and this runs thirty times a second. Not mid-scrub either: every step
            // of one seeks anyway, and a sync-sample seek lands up to a GOP from
            // where it was asked, so "correcting" it only asks for the same keyframe.
            !park && !scrubbing && player.playbackState == Player.STATE_READY &&
                abs(player.currentPosition - wanted) > VIDEO_RESYNC_MS &&
                now - s.lastCorrection > CORRECTION_GAP_MS -> {
                s.lastCorrection = now
                player.seekTo(wanted)
            }
        }

        // The clip's own level, every tick: cheap, and a slider or the camera
        // switch is heard at once. A shot's is under the camera sound for the
        // edit, an overlay's is its own - the export's rule (OverlayRules) -
        // and through its fades, as the export plays them (FadeProcessor).
        val level = when {
            park || muted -> 0f
            else -> OverlayRules.effectiveVolume(clip, muteOriginal, originalVolume, clip.volumeAtTimeline(t)) *
                AudioRules.fadeGain(t - clip.timelineStartMs, clip.durationMs, clip.fadeInMs, clip.fadeOutMs)
        }
        if (player.volume != level) player.volume = level

        val run = playing && !park
        if (run && !player.playWhenReady) player.play()
        if (!run && player.playWhenReady) player.pause()
    }

    /**
     * Lets go of what a surface has loaded, decoder and all. An overlay row with
     * nothing near the playhead gives its decoder back, so six rows of footage
     * do not hold six decoders for the whole edit; the lookahead loads it again
     * before its next clip is due.
     */
    private fun unload(s: Surface) {
        if (s.loadedUri == null) return
        runCatching {
            s.player.stop()
            s.player.clearMediaItems()
        }
        s.loadedUri = null
        s.activeClipId = null
        s.shownClipId = null
        s.firstFrameFor = null
    }

    private fun switchTo(s: Surface, clipId: String) {
        s.activeClipId = clipId
        s.readyTicks = 0
        s.firstFrameFor = null
        s.forceSeek = false
    }

    /**
     * Gets a player moving again when it has stopped making progress, one
     * surface at a time.
     *
     * The old version watched only the clock's player, reloaded every surface
     * when it fired - tearing down the healthy roll and every overlay with it -
     * and let go of the surface and took it back, which blocks the main thread on
     * a stuck playback thread. This reloads just the player that is stuck, only
     * once it has stopped loading, with a wait that doubles each time; see
     * [StallWatch].
     */
    private fun watchStalls(now: Long) {
        allSurfaces().forEach { s ->
            val player = s.player
            if (s.loadedUri == null || s.activeClipId == null) {
                s.stall.healthy()
                return@forEach
            }
            val state = player.playbackState
            val position = player.currentPosition
            // "Playing" with a position that will not move is as stuck as waiting.
            val frozen = playing && s.covering && state == Player.STATE_READY &&
                player.playWhenReady && position == s.lastPosition
            s.lastPosition = position
            val stuck = when {
                state == Player.STATE_BUFFERING -> s.stall.stuck(now, player.bufferedPosition)
                frozen -> s.stall.stuck(now, 0L)
                else -> {
                    s.stall.healthy()
                    false
                }
            }
            if (!stuck) return@forEach
            Log.w(TAG, "reloading ${s.key}: no progress; next wait ${s.stall.waitMs} ms")
            // Loaded again from scratch on the next sync: the file set, prepared
            // and parked where the playhead is. Another seek does not help; a seek
            // is what it is stuck on.
            runCatching {
                player.stop()
                player.clearMediaItems()
            }
            s.loadedUri = null
            s.activeClipId = null
            // The view still holds the frame from before the stop. Left marked as
            // shown, the same clip coming back on the next sync would count as
            // drawn at once and put that stale frame up - into a blend, too.
            s.shownClipId = null
            s.firstFrameFor = null
        }
    }

    private fun retryDelayMs(failures: Int): Long =
        if (failures <= 1) 0L else (RETRY_BASE_MS shl (failures - 2).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)

    private fun covers(clip: Clip, t: Long): Boolean =
        t >= clip.timelineStartMs && t < clip.timelineEndMs

    private fun playbackUriFor(clip: Clip): Uri? {
        val source = clip.uri ?: fallbackUri ?: return null
        // Heavy footage previews from its light copy, whichever file it is and
        // whichever row it is on; export never comes through here.
        return proxies[source] ?: source
    }

    // ---- Sound --------------------------------------------------------------------

    private fun syncAudio(t: Long, transportRunning: Boolean) {
        // Mid-scrub a sound is only paused: positioning it on every pointer event
        // was hundreds of seeks a second with a few tracks. It is positioned once,
        // when the scrub ends.
        val holdPosition = scrubbing
        audioClips.forEach { clip ->
            val player = audioPlayers[clip.id] ?: return@forEach
            val rate = audioRates.getOrPut(clip.id) { Rate() }

            if (!covers(clip, t)) {
                if (player.playWhenReady) player.pause()
                if (holdPosition) return@forEach
                // A cue coming up shortly is positioned now, while nothing is
                // waiting on the seek, so it is buffered and parked on the right
                // sample by the time it is due.
                val untilDue = clip.timelineStartMs - t
                if (untilDue in 1..PREROLL_MS && !primed.contains(clip.id)) {
                    val opening = clip.speedAt(clip.timelineStartMs)
                    setSpeed(player, rate, clip.id, opening, pitch = pitchFor(clip, opening))
                    player.seekTo(clip.sourceInMs)
                    primed.add(clip.id)
                }
                // Only a cue that is now behind us is worth re-priming, and only
                // once we are past it far enough that a rewind is a real jump.
                if (t >= clip.timelineEndMs || untilDue > PREROLL_MS) primed.remove(clip.id)
                return@forEach
            }

            // The level up to the player's ceiling, through the fades; the part
            // above it and the clip's voice go to the chain in front of its
            // sink - the same split the export writes (AudioMixing, FadeProcessor).
            // The level at this moment: the keys over the clip, or its one number.
            val (level, boost) = AudioRules.gainSplit(clip.volumeAtTimeline(t))
            val fade = AudioRules.fadeGain(t - clip.timelineStartMs, clip.durationMs, clip.fadeInMs, clip.fadeOutMs)
            player.volume = if (muted) 0f else level * fade
            audioChains[clip.id]?.let { chain ->
                chain.gain.set(boost)
                chain.voice.set(clip.voice)
            }
            val speed = clip.speedAt(t)
            setSpeed(player, rate, clip.id, speed, pitch = pitchFor(clip, speed))
            val wanted = clip.sourceAt(t).coerceAtLeast(0L)

            if (!holdPosition) {
                if (!primed.contains(clip.id)) {
                    // Only reached when a cue was jumped into with no warning - a scrub
                    // straight into the middle of it, or a sound moved under the
                    // playhead. Everywhere else it is already parked, because chasing
                    // drift with seeks forces a re-buffer, the re-buffer causes more
                    // drift, and that drift triggers the next seek: a loop that
                    // sounds exactly like audio breaking up.
                    player.seekTo(wanted)
                    primed.add(clip.id)
                } else if (abs(player.currentPosition - wanted) > AUDIO_RESYNC_MS) {
                    player.seekTo(wanted)
                }
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
            audioRates.remove(id)
            audioChains.remove(id)
            primed.remove(id)
        }

        audioClips.forEach { clip ->
            val uri = clip.uri ?: return@forEach
            val existing = audioPlayers[clip.id]
            when {
                existing == null -> {
                    val chain = AudioChain().also {
                        it.gain.set(AudioRules.gainSplit(clip.volume).second)
                        it.voice.set(clip.voice)
                    }
                    audioChains[clip.id] = chain
                    audioPlayers[clip.id] = newAudioPlayer(chain).apply {
                        setMediaItem(MediaItem.fromUri(uri))
                        // Prepared the moment the track is added, so the first play
                        // is not also the first read off storage.
                        prepare()
                        volume = AudioRules.gainSplit(clip.volume).first
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

    /** Binds a view to the player it shows - base or overlay, the one way in for both. */
    fun attachSurface(player: ExoPlayer, view: TextureView) {
        if (released) return
        player.setVideoTextureView(view)
        // A still picture resized - a sheet opening shrinks the preview - kept
        // the frame it had at the old size, drawn at the old scale into the new
        // box: a third smaller and off to one side, until something drew again.
        // After the picture had played, the effects pass kept drawing into a
        // surface of the old size, even new frames after a seek - measured:
        // a 370x658 picture in the bottom-left of a 557x991 buffer. The view
        // is handed to the player again, which makes it a surface of the new
        // size, and a seek to where it is draws a frame into it.
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (!resized || oldRight - oldLeft == 0) return@addOnLayoutChangeListener
            v.post {
                if (released || player.isPlaying || player.playbackState == Player.STATE_IDLE) return@post
                player.clearVideoTextureView(view)
                player.setVideoTextureView(view)
                val at = player.currentPosition
                player.seekTo(if (player.playbackState == Player.STATE_ENDED) (at - REDRAW_BACK_MS).coerceAtLeast(0L) else at)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        allSurfaces().forEach { it.player.release() }
        audioPlayers.values.forEach { it.release() }
        overlaySurfaces.clear()
        audioPlayers.clear()
        audioSources.clear()
        audioRates.clear()
        audioChains.clear()
        primed.clear()
    }

    private companion object {
        const val TAG = "SquishPreview"

        const val KEY_A = "base-a"
        const val KEY_B = "base-b"
        const val KEY_OVERLAY = "overlay-"

        /** How far back an ended player is sent to draw its last picture again: under two frames. */
        const val REDRAW_BACK_MS = 50L

        /**
         * How far ahead the next shot is opened and parked. Longer than the
         * second the plan started from: on the phone a cold open of a photo clip
         * with its effect chain took most of two seconds.
         */
        const val LOOKAHEAD_MS = 1_500L

        /**
         * How far from anything on its row an overlay's player has to be before
         * it lets its decoder go. Well past the lookahead, so a row is never
         * unloaded and loaded again for one clip.
         */
        const val IDLE_RELEASE_MS = 5_000L

        /** Ticks a player must sit ready after a switch before its surface is trusted without the callback. */
        const val READY_TICKS = 2

        /** Paused redraws, at most this often while a slider streams changes. */
        const val REDRAW_GAP_MS = 80L

        const val DETACH_TIMEOUT_MS = 1_000L

        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 16_000L

        /** Two failures on one surface within this are one problem, not two; see the error listener. */
        const val ERROR_MEMORY_MS = 30_000L
        /** How long a chain taken off after a fault waits before it is put back on with a load. */
        const val CHAIN_RETRY_MS = 15_000L

        /** Generous on purpose: correction is for a jump, not for playback. */
        const val AUDIO_RESYNC_MS = 400L
        const val VIDEO_RESYNC_MS = 120L

        /** How far ahead of a cue its decoder is positioned and buffered. */
        const val PREROLL_MS = 2_000L

        /**
         * The shortest gap between two corrective seeks on the same surface.
         *
         * A seek is not free - it drops the decoder's buffer and restarts the
         * frames through the effect chain. Anything that can ask for one on every
         * frame has to be stopped from doing so, whatever the reason it thinks it
         * has.
         */
        const val CORRECTION_GAP_MS = 400L
    }
}

/** A grade that changes nothing: unit gain, no contrast or saturation shift, no look, no slider. */
private val IDENTITY_GRADE = Looks.grade(null, 1f)
