package com.squish.app.media

import com.squish.app.timeline.Clip
import com.squish.app.timeline.Transform
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.transformAt

/**
 * The decisions an export makes, with Media3 kept out of them.
 *
 * Which clip goes on which layer, what each layer looks like at a given moment of
 * a transition, how a speed change moves a keyframe, how much of a song is read
 * and how six channels fold into two. Every one of those is arithmetic, and every
 * one of them was wrong at some point in a way nobody could see until a file was
 * rendered on a phone. Here they are plain functions, so tools/jvm runs them
 * against real numbers; VideoProcessor and CompositionFactory only translate the
 * answers into Media3 objects.
 */
object ExportPlan {

    // ---- Layers ----------------------------------------------------------------

    enum class Role {
        /**
         * The compositor's primary input: one transparent still the length of the
         * edit, never visible. Media3 draws the primary on top and takes the
         * output's timestamps from it, and neither is something a real layer should
         * be doing - an overlay as primary would end the export when it ended, and
         * a base roll as primary would be drawn over every picture-in-picture.
         */
        Clock,
        Overlay,
        Base
    }

    /** One sequence of a composited export. [clips] are in time order and never overlap. */
    data class Layer(val role: Role, val clips: List<Clip>)

    /**
     * The layers of a composited export, top of the picture first - which is also
     * the order the sequences are handed to Media3, because its compositor draws
     * the first input on top and each later one underneath the last.
     */
    data class Layers(val layers: List<Layer>, val endMs: Long) {
        val baseLayers: List<Layer> get() = layers.filter { it.role == Role.Base }
        val baseRolls: List<List<Clip>> get() = baseLayers.map { it.clips }
    }

    fun layers(videoClips: List<Clip>): Layers {
        val base = videoClips.filter { !it.isOverlay && it.durationMs > 0 }.sortedBy { it.timelineStartMs }
        val overlays = videoClips.filter { it.isOverlay && it.durationMs > 0 }
        val end = videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L

        val out = mutableListOf(Layer(Role.Clock, emptyList()))
        // Highest layer on top. A layer whose clips overlap in time spills onto an
        // extra sequence just above it rather than losing one of them; the later
        // clip is the one drawn on top, as the preview shows it.
        overlays.map { it.layer }.distinct().sortedDescending().forEach { layer ->
            val tracks = stack(overlays.filter { it.layer == layer }.sortedBy { it.timelineStartMs })
            tracks.asReversed().forEach { out.add(Layer(Role.Overlay, it)) }
        }
        dealRolls(base).forEach { out.add(Layer(Role.Base, it)) }
        return Layers(out, end)
    }

    /**
     * Whether the edit needs the compositor at all. A base track that runs end to
     * end from zero, with nothing floating over it and nothing overlapping, is one
     * sequence of clips played one after another - the cheapest export there is,
     * and the only one Media3 can copy frames through untouched.
     *
     * "End to end" is held to the same [MIN_GAP_MS] that [pieces] uses, and
     * measured the same way, as the slip it adds up to. A gap of a few
     * milliseconds left by rounding a trim or a drag used to send the edit to the
     * compositor, where [pieces] dropped it anyway - the new path, for nothing.
     * Played end to end instead, each such gap starts everything after it that
     * much early, and nothing makes the slip good, so it is only allowed while
     * the whole of it stays under [MIN_GAP_MS]: no clip, sound or caption is
     * then further out than [pieces] would have left it.
     */
    fun needsCompositing(videoClips: List<Clip>): Boolean {
        if (videoClips.any { it.isOverlay && it.durationMs > 0 }) return true
        if (videoClips.any { !it.isOverlay && it.transitionIn.isActive }) return true
        val base = videoClips.filter { !it.isOverlay && it.durationMs > 0 }.sortedBy { it.timelineStartMs }
        if (base.isEmpty()) return false
        // A first clip dragged to before zero is played from its start, as it
        // always was here; only a late one leaves something to make up.
        var slip = base.first().timelineStartMs.coerceAtLeast(0L)
        if (slip >= MIN_GAP_MS) return true
        for ((a, b) in base.zipWithNext()) {
            val gap = b.timelineStartMs - a.timelineEndMs
            // Overlapping shots are two on screen at once, which one sequence
            // cannot play.
            if (gap < 0L) return true
            slip += gap
            if (slip >= MIN_GAP_MS) return true
        }
        return false
    }

    /**
     * The base track dealt onto as few rolls as it needs.
     *
     * A clip that starts at or after the end of the one before it stays on the
     * same roll - a gap costs nothing, a second roll costs a decoder. A clip that
     * overlaps the one before it (a transition, or a clip dragged over another)
     * goes onto another roll whose last clip has finished, and only when none has
     * does a new one open. Earlier rolls are drawn on top.
     */
    fun dealRolls(base: List<Clip>): List<List<Clip>> {
        val rolls = mutableListOf<MutableList<Clip>>()
        var previousRoll = -1
        var previousEnd = Long.MIN_VALUE
        for (clip in base.sortedBy { it.timelineStartMs }) {
            val start = clip.timelineStartMs
            val chosen = when {
                previousRoll >= 0 && start >= previousEnd -> previousRoll
                else -> rolls.indices.firstOrNull { it != previousRoll && rolls[it].last().timelineEndMs <= start }
                    ?: rolls.size.also { rolls.add(mutableListOf()) }
            }
            rolls[chosen].add(clip)
            previousRoll = chosen
            previousEnd = clip.timelineEndMs
        }
        return rolls
    }

    /** Clips of one overlay layer onto as few non-overlapping tracks as they need, earliest first. */
    private fun stack(clips: List<Clip>): List<List<Clip>> {
        val tracks = mutableListOf<MutableList<Clip>>()
        for (clip in clips) {
            val fits = tracks.firstOrNull { it.last().timelineEndMs <= clip.timelineStartMs }
            if (fits != null) fits.add(clip) else tracks.add(mutableListOf(clip))
        }
        return tracks
    }

    /** One stretch of a sequence: nothing for a while, or a clip. */
    sealed class Piece {
        abstract val durationMs: Long
        data class Gap(override val durationMs: Long) : Piece()
        data class Item(val clip: Clip) : Piece() {
            override val durationMs: Long get() = clip.durationMs
        }
    }

    /**
     * A layer as a sequence: its clips at their places, blank in between, and blank
     * after the last one up to the end of the edit.
     *
     * The trailing blank is not tidiness. A layer that simply stopped would leave
     * the compositor holding its last frame - it pairs every output frame with the
     * nearest frame each input has, and an ended input's nearest frame is its last
     * one - so a picture-in-picture that ends at five seconds would sit frozen on
     * screen until the end of the video.
     *
     * Positions are counted from what has actually been laid down, not from where
     * the timeline says the last clip was. A gap too short to be worth a frame is
     * skipped, which starts the next clip those few milliseconds early; the gap
     * after it is then measured from where that clip really ended, so the slip is
     * made up at the next chance instead of being carried to the end of the edit.
     * No clip is ever more than [MIN_GAP_MS] from its place on the timeline, the
     * same bound [needsCompositing] holds the one-sequence export to.
     *
     * The clock is always one stretch, however short the edit. CompositionFactory
     * hides its first input on the understanding that it is the clock; an edit
     * under [MIN_GAP_MS] long used to get no clock sequence at all, and the real
     * top layer took its place and was drawn at nothing.
     */
    fun pieces(layer: Layer, endMs: Long): List<Piece> {
        if (layer.role == Role.Clock) return listOf(Piece.Gap(endMs.coerceAtLeast(1L)))
        val out = mutableListOf<Piece>()
        var laid = 0L
        for (clip in layer.clips) {
            val gap = clip.timelineStartMs - laid
            if (gap >= MIN_GAP_MS) {
                out.add(Piece.Gap(gap))
                laid += gap
            }
            out.add(Piece.Item(clip))
            laid += clip.durationMs
        }
        val tail = endMs - laid
        if (tail >= MIN_GAP_MS) out.add(Piece.Gap(tail))
        return out
    }

    /**
     * Below this a gap is rounding, not a gap: shorter than a frame at 50 fps, so
     * a blank still for it would be one frame of nothing or none at all.
     */
    const val MIN_GAP_MS = 20L

    // ---- Transitions ---------------------------------------------------------

    /**
     * How one base clip is drawn at a moment.
     *
     * [alpha] is its opacity. [shiftX] moves the picture right by that fraction of
     * the frame, uncovering nothing behind it. Only the part of the frame from
     * [keepFrom] to [keepTo] (fractions of its width) is drawn at all.
     */
    data class Draw(
        val alpha: Float = 1f,
        val shiftX: Float = 0f,
        val keepFrom: Float = 0f,
        val keepTo: Float = 1f
    ) {
        val isPlain: Boolean get() = alpha >= 1f && shiftX == 0f && keepFrom <= 0f && keepTo >= 1f
    }

    val PLAIN = Draw()
    private val HIDDEN = Draw(alpha = 0f)

    /**
     * Every base clip showing at [timeUs] and how it is drawn there.
     *
     * Mirrors PreviewEngine.blend - the incoming shot is the later one, progress
     * runs over the overlap, a dissolve fades the new shot up, a dip goes through
     * black, a slide pushes the new shot in from the right over the old one, a wipe
     * reveals it from the left edge. What the preview does by stacking views the
     * export has to do with a fixed stack: the compositor always draws the upper
     * roll on top, so when the incoming shot is on the lower roll, the outgoing
     * one is faded out, or cut away, over it instead - with the same result on
     * screen.
     *
     * [rolls] are the base layers top first.
     */
    fun baseDrawsAt(rolls: List<List<Clip>>, timeUs: Long): Map<String, Draw> {
        // (clip, roll) for everything on screen, in the order the shots began.
        val showing = ArrayList<Pair<Clip, Int>>(3)
        for (r in rolls.indices) {
            for (clip in rolls[r]) if (covers(clip, timeUs)) showing.add(clip to r)
        }
        if (showing.isEmpty()) return emptyMap()
        showing.sortBy { it.first.timelineStartMs }
        val (inClip, inRoll) = showing[showing.size - 1]
        if (showing.size == 1) return mapOf(inClip.id to PLAIN)
        val (outClip, outRoll) = showing[showing.size - 2]

        val overlapUs = (outClip.timelineEndMs - inClip.timelineStartMs) * 1_000L
        val p = if (overlapUs <= 0L) 1f
        else ((timeUs - inClip.timelineStartMs * 1_000L).toDouble() / overlapUs).toFloat().coerceIn(0f, 1f)
        val type = if (inClip.transitionIn.isActive) inClip.transitionIn.type else TransitionType.None
        val (inDraw, outDraw) = blend(type, p, incomingOnTop = inRoll < outRoll)

        val result = HashMap<String, Draw>(showing.size * 2)
        // A third shot still running under a transition is covered by it anyway;
        // the preview shows only the two, and so does the file.
        for (i in 0 until showing.size - 2) result[showing[i].first.id] = HIDDEN
        result[inClip.id] = inDraw
        result[outClip.id] = outDraw
        return result
    }

    /** (incoming, outgoing) for a transition [p] of the way through. */
    fun blend(type: TransitionType, p: Float, incomingOnTop: Boolean): Pair<Draw, Draw> = when (type) {
        // Overlapping with nothing asked for: a hard cut to the new shot.
        TransitionType.None -> PLAIN to HIDDEN

        // top * a + bottom * (1 - a) whichever of the two is on top.
        TransitionType.CrossFade ->
            if (incomingOnTop) Draw(alpha = p) to PLAIN
            else PLAIN to Draw(alpha = 1f - p)

        // Only one shot is ever showing, so the stack does not matter.
        TransitionType.DipToBlack ->
            if (p < 0.5f) HIDDEN to Draw(alpha = 1f - 2f * p)
            else Draw(alpha = 2f * p - 1f) to HIDDEN

        TransitionType.SlideLeft ->
            if (incomingOnTop) Draw(shiftX = 1f - p) to PLAIN
            // The old shot stays put and is cut away where the new one has arrived.
            else Draw(shiftX = 1f - p) to Draw(keepTo = 1f - p)

        TransitionType.WipeRight ->
            if (incomingOnTop) Draw(keepTo = p) to PLAIN
            else PLAIN to Draw(keepFrom = p)
    }

    /**
     * How [clip] is drawn at [timeUs]. Outside its own span, or anywhere it is the
     * only shot, it is drawn as it is.
     */
    fun drawAt(rolls: List<List<Clip>>, clip: Clip, timeUs: Long): Draw =
        baseDrawsAt(rolls, timeUs)[clip.id] ?: PLAIN

    /**
     * The rolls cut down to [clip] and whatever overlaps it - everything that can
     * ever share the screen with it. A clip's transition effect asks for its draw
     * on every frame, and walking the whole edit thirty times a second for an
     * answer only two or three clips can affect is waste.
     */
    fun neighbourhood(rolls: List<List<Clip>>, clip: Clip): List<List<Clip>> =
        rolls.map { roll -> roll.filter { it.id == clip.id || overlaps(it, clip) } }

    /** Whether [clip] ever shares the screen with another base shot: a transition, or a cut over one. */
    fun takesPartInBlend(rolls: List<List<Clip>>, clip: Clip): Boolean =
        rolls.any { roll -> roll.any { it.id != clip.id && overlaps(it, clip) } }

    /**
     * Where on the timeline a frame of [clip] falls, from how far into the played
     * clip it is. Frames reach the transition effect after the speed change, so
     * what they carry is played time.
     */
    fun timelineUs(clip: Clip, playedElapsedUs: Long): Long =
        clip.timelineStartMs * 1_000L + playedElapsedUs.coerceAtLeast(0L)

    private fun overlaps(a: Clip, b: Clip) =
        a.timelineStartMs < b.timelineEndMs && b.timelineStartMs < a.timelineEndMs

    private fun covers(clip: Clip, timeUs: Long): Boolean =
        timeUs >= clip.timelineStartMs * 1_000L && timeUs < clip.timelineEndMs * 1_000L

    // ---- Motion clocks -----------------------------------------------------------

    /**
     * Which part of a clip's placement a transform effect applies. The stabilizer
     * belongs to the footage and is applied before the edit's rotation; what the
     * editor asked for is applied after it, on the picture as it is seen.
     */
    enum class MotionPart { Stabilizer, User }

    /**
     * A clip's motion for the frame [sourceElapsedMs] into its source window.
     *
     * Frames reach the transform before the speed change, so what they carry is
     * source time. The stabilizer wants exactly that; keyframes were drawn on the
     * played clip and want played time, which the ramp converts to. Handing
     * keyframes the source clock was the bug: at half speed a push-in reached only
     * half-way, at double speed it finished at the clip's midpoint and held.
     */
    fun motionAt(clip: Clip, part: MotionPart, sourceElapsedMs: Long): Transform = when (part) {
        MotionPart.Stabilizer ->
            if (clip.stabilizer.isEmpty()) Transform.Identity
            else clip.stabilizer.transformAt(clip.sourceInMs + sourceElapsedMs, Transform.Identity)
        MotionPart.User -> {
            val played = if (clip.speedRamp.isIdentity) sourceElapsedMs
            else clip.speedRamp.outputOffsetAt(sourceElapsedMs, clip.sourceSpanMs)
            clip.keyframes.transformAt(played, clip.staticTransform)
        }
    }

    fun hasMotion(clip: Clip, part: MotionPart): Boolean = when (part) {
        MotionPart.Stabilizer -> clip.stabilizer.isNotEmpty()
        MotionPart.User -> clip.keyframes.isNotEmpty() || !clip.staticTransform.isIdentity
    }

    /**
     * A placement as a 2D affine matrix in normalized device coordinates, row
     * major as (a, b, c, d, tx, ty): x' = a*x + b*y + tx, y' = c*x + d*y + ty.
     *
     * NDC runs -1..1 on both axes whatever the frame's shape, so a rotation done
     * directly in it shears anything that is not square: a 16:9 frame turned by
     * 30 degrees came out as a leaning parallelogram. The turn is done in square
     * units instead - x stretched by the frame's [aspect] (width over height)
     * first and squeezed back after - which is the rotation the preview's view
     * applies. +Y is up in NDC and down on screen, so the offset's Y is negated,
     * and the angle too: the editor's degrees are clockwise as seen, the way the
     * preview's graphicsLayer turns the picture. The old export turned the same
     * number the other way, so a tilted PiP leaned left in the file and right on
     * screen.
     */
    fun placementMatrix(t: Transform, aspect: Float): FloatArray {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val radians = Math.toRadians(-t.rotationDegrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val s = t.scale
        // S(1/a) * R * S(a), each axis then scaled by s.
        return floatArrayOf(
            s * cos, s * (-sin) / a,
            s * sin * a, s * cos,
            t.offsetXFraction, -t.offsetYFraction
        )
    }

    // ---- Sound -----------------------------------------------------------------

    /**
     * How much of a sound file to read, and where it starts in the edit.
     *
     * [leadMs] of silence first, then the file from [sourceInMs] to [sourceOutMs].
     * The window is in source time and the room left in the edit is in played
     * time, so the one is converted through the clip's ramp into the other: a 60 s
     * song at 2x plays 30 s and must be read in full, not cut to 30 s of source and
     * then sped up into 15. [skippedSourceMs] is how much of the clip's own window
     * was left out at the front, for a clip dragged to start before zero, so its
     * ramp can be cut to match.
     */
    data class AudioSlice(val leadMs: Long, val sourceInMs: Long, val sourceOutMs: Long, val skippedSourceMs: Long = 0L)

    fun audioSlice(clip: Clip, editEndMs: Long): AudioSlice? {
        val span = clip.sourceSpanMs
        if (span <= 0L) return null
        // A clip dragged to start before zero is heard from where zero falls in it.
        val skippedPlayed = (-clip.timelineStartMs).coerceAtLeast(0L)
        val lead = clip.timelineStartMs.coerceAtLeast(0L)
        val room = editEndMs - lead
        if (room <= 0L) return null
        val playedEnd = minOf(clip.durationMs, skippedPlayed + room)
        if (playedEnd <= skippedPlayed) return null

        val skippedSource = if (skippedPlayed == 0L) 0L else clip.speedRamp.sourceOffsetAt(skippedPlayed, span)
        val endSource = if (playedEnd >= clip.durationMs) span else clip.speedRamp.sourceOffsetAt(playedEnd, span)
        var sourceOut = clip.sourceInMs + endSource
        sourceOut = minOf(sourceOut, clip.sourceOutMs)
        if (clip.sourceDurationMs > 0L) sourceOut = minOf(sourceOut, clip.sourceDurationMs)
        val sourceIn = clip.sourceInMs + skippedSource
        if (sourceOut <= sourceIn) return null
        return AudioSlice(lead, sourceIn, sourceOut, skippedSource)
    }

    /** The most channels an input is folded down from; eight is 7.1. */
    const val MAX_INPUT_CHANNELS = 8

    /** Mono stays mono and stereo stays stereo; anything wider becomes stereo. */
    fun downmixOutputChannels(inputChannels: Int): Int = if (inputChannels <= 2) inputChannels else 2

    /**
     * The fold-down matrix for [inputChannels], row by input channel, column by
     * output channel, as ChannelMixingMatrix takes it.
     *
     * Channel orders are Android's (and AAC's as decoded): 3 is L R C; 4 is L R and
     * the two backs; 5 is L R C and the backs; 6 (5.1) is L R C LFE and the backs;
     * 7 adds a back centre; 8 (7.1) is L R C LFE, the backs and the sides. Centre
     * and surrounds go in at -3 dB, the back centre at -6 dB to each side, and the
     * low-frequency channel is left out as the ITU fold-down does - it is there for
     * a subwoofer, and a phone speaker playing it only muddies the dialogue.
     *
     * Each output is then scaled so its coefficients sum to at most one. A full
     * scale signal in every channel at once cannot clip, which is what a film's
     * loudest moment is; the price is that a fold-down is quieter than the stereo
     * mix a film might also carry, which is the ordinary trade every player makes.
     */
    fun downmixCoefficients(inputChannels: Int): FloatArray {
        val n = inputChannels.coerceIn(1, MAX_INPUT_CHANNELS)
        if (n <= 2) return FloatArray(n * n) { if (it / n == it % n) 1f else 0f }
        val side = 0.7071f
        val back = 0.5f
        // Per input channel: (to left, to right).
        val pairs: List<Pair<Float, Float>> = when (n) {
            3 -> listOf(1f to 0f, 0f to 1f, side to side)
            4 -> listOf(1f to 0f, 0f to 1f, side to 0f, 0f to side)
            5 -> listOf(1f to 0f, 0f to 1f, side to side, side to 0f, 0f to side)
            6 -> listOf(1f to 0f, 0f to 1f, side to side, 0f to 0f, side to 0f, 0f to side)
            7 -> listOf(1f to 0f, 0f to 1f, side to side, 0f to 0f, side to 0f, 0f to side, back to back)
            else -> listOf(
                1f to 0f, 0f to 1f, side to side, 0f to 0f,
                side to 0f, 0f to side, side to 0f, 0f to side
            )
        }
        val leftSum = pairs.sumOf { it.first.toDouble() }.toFloat()
        val rightSum = pairs.sumOf { it.second.toDouble() }.toFloat()
        val leftScale = if (leftSum > 1f) 1f / leftSum else 1f
        val rightScale = if (rightSum > 1f) 1f / rightSum else 1f
        val out = FloatArray(n * 2)
        pairs.forEachIndexed { i, (l, r) ->
            out[i * 2] = l * leftScale
            out[i * 2 + 1] = r * rightScale
        }
        return out
    }
}
