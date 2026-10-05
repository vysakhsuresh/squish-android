package com.squish.app.media

import com.squish.app.timeline.Clip
import com.squish.app.timeline.Transform
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.animated
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
         * The compositor's primary input: one empty stretch the length of the
         * edit, never visible. Media3 draws the primary on top and takes the
         * output's timestamps from it, and neither is something a real layer should
         * be doing - an overlay as primary would end the export when it ended, and
         * a base roll as primary would be drawn over every picture-in-picture.
         */
        Clock,
        Overlay,
        Base,
        /**
         * The canvas's background, under every roll: the stills a padded frame
         * is filled with round the picture (CanvasBackdrop). Picture only, and
         * never sound.
         */
        Backdrop
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

    /**
     * @param endMs where the edit ends: the last picture's end, or further when a
     *   sound has been dragged out past it - the file runs to the sound's end,
     *   black under it, as the preview plays it. Left out, the picture's end.
     * @param backdrop the stills of a padded canvas's background, in time order
     *   and never overlapping, laid under every roll; none for a frame the
     *   picture fills.
     */
    fun layers(videoClips: List<Clip>, endMs: Long = pictureEnd(videoClips), backdrop: List<Clip> = emptyList()): Layers {
        val base = videoClips.filter { !it.isOverlay && it.durationMs > 0 }.sortedBy { it.timelineStartMs }
        val overlays = videoClips.filter { it.isOverlay && it.durationMs > 0 }
        val end = maxOf(endMs, pictureEnd(videoClips))

        val out = mutableListOf(Layer(Role.Clock, emptyList()))
        // Highest layer on top. A layer whose clips overlap in time spills onto an
        // extra sequence just above it rather than losing one of them; the later
        // clip is the one drawn on top, as the preview shows it.
        overlays.map { it.layer }.distinct().sortedDescending().forEach { layer ->
            val tracks = stack(overlays.filter { it.layer == layer }.sortedBy { it.timelineStartMs })
            tracks.asReversed().forEach { out.add(Layer(Role.Overlay, it)) }
        }
        dealRolls(base).forEach { out.add(Layer(Role.Base, it)) }
        if (backdrop.isNotEmpty()) out.add(Layer(Role.Backdrop, backdrop.sortedBy { it.timelineStartMs }))
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
    fun needsCompositing(videoClips: List<Clip>, endMs: Long = pictureEnd(videoClips), padded: Boolean = false): Boolean {
        // A background under the picture is a second layer by definition.
        if (padded) return true
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
        // A sound running on past the last shot: one sequence of shots would end
        // with them and the file would stop short of what the timeline plays.
        slip += (endMs - base.last().timelineEndMs).coerceAtLeast(0L)
        return slip >= MIN_GAP_MS
    }

    /** Where the last picture ends - the shots and the overlays. */
    fun pictureEnd(videoClips: List<Clip>): Long = videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L

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

    /** One stretch of a sequence: Media3's own gap, a transparent still, or a clip. */
    sealed class Piece {
        abstract val durationMs: Long

        /**
         * Media3's own gap: silence, and black frames at its fixed 30 fps. Only
         * ever the clock's opening frame - see [pieces] - and, in a sound-only
         * export, every empty stretch.
         */
        data class Gap(override val durationMs: Long) : Piece()

        /** A transparent still at the edit's frame rate: a layer's empty stretch, and the clock's body. */
        data class Clear(override val durationMs: Long) : Piece()

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
     * The clock is always there, however short the edit. CompositionFactory hides
     * its first input on the understanding that it is the clock; an edit under
     * [MIN_GAP_MS] long used to get no clock sequence at all, and the real top
     * layer took its place and was drawn at nothing. It opens on Media3's own gap,
     * [clockLeadMs] long, and is a transparent still from there to the end:
     *
     *  - the gap first, because its loader is what makes the export's sound and
     *    picture exporters in the right order ([sequenceTracks] says why), and
     *    only the first item of a sequence gets to do that;
     *  - the still after it, because the clock is the compositor's primary and
     *    the file gets exactly one frame per primary frame, stamped with its
     *    time - and a gap's frames come at a fixed 30 fps whatever the footage.
     *    A whole clock of gap wrote every layered export at 30 fps: 60 fps
     *    footage under a PiP lost every other frame, and 24 fps juddered.
     *    The still runs at the edit's own rate, so after its first frame the
     *    file does too.
     */
    fun pieces(layer: Layer, endMs: Long, clockLeadMs: Long = clockLeadMs(GAP_FPS)): List<Piece> {
        if (layer.role == Role.Clock) {
            val end = endMs.coerceAtLeast(1L)
            val lead = clockLeadMs.coerceIn(1L, end)
            // An edit too short for a still after the frame is the frame alone.
            return if (end - lead < MIN_GAP_MS) listOf(Piece.Gap(end)) else listOf(Piece.Gap(lead), Piece.Clear(end - lead))
        }
        val out = mutableListOf<Piece>()
        var laid = 0L
        for (clip in layer.clips) {
            val gap = clip.timelineStartMs - laid
            if (gap >= MIN_GAP_MS) {
                out.add(Piece.Clear(gap))
                laid += gap
            }
            out.add(Piece.Item(clip))
            laid += clip.durationMs
        }
        val tail = endMs - laid
        if (tail >= MIN_GAP_MS) out.add(Piece.Clear(tail))
        return out
    }

    /**
     * Below this a gap is rounding, not a gap: shorter than a frame at 50 fps, so
     * a blank still for it would be one frame of nothing or none at all. The
     * model's own number, so an overlay's join is butted by the same rule.
     */
    const val MIN_GAP_MS = com.squish.app.timeline.MIN_GAP_MS

    /** The rate Media3 1.11.1 draws a gap's blank frames at (SequenceAssetLoader.insertBlankFrames), not a choice. */
    const val GAP_FPS = 30

    /**
     * How long the clock's opening gap is for an edit at [frameRate]: one frame,
     * so the still that follows starts where the second frame is due and the
     * file's cadence is even from the first frame. One frame of the gap's own
     * rate when the edit is slower than that - a longer gap would draw a second
     * blank frame at 33 ms, and the file would carry a duplicate at the start;
     * one short interval is the lesser blemish.
     */
    fun clockLeadMs(frameRate: Int): Long =
        Math.round(1000.0 / maxOf(frameRate, GAP_FPS).coerceAtLeast(1)).coerceAtLeast(1L)

    // ---- What each sequence declares ---------------------------------------------

    /** The tracks a sequence is declared with. */
    data class Tracks(val video: Boolean, val sound: Boolean)

    /**
     * The tracks each of [layers] declares, in the same order; null for a layer
     * that is not built at all.
     *
     * A sound-only export builds the base rolls and the overlay rows that are
     * [heard], each as sound alone. Otherwise a base roll carries the clips' own
     * sound when [baseAudio], an overlay row when any clip on it is heard, and
     * the rest are picture only.
     *
     * The clock declares sound whenever any other sequence does. That is not for
     * anything it plays - it is silent - but for the order Media3 1.11.1 starts
     * the layers in. The lowest sequence that declares a track is the one that
     * creates that track's exporter, and a sequence declared with sound whose
     * first item has none (a photo, a blank, the transparent still that fills an
     * empty stretch) asks for a sound track to be forced with
     * checkNotNull(listener.onOutputFormat(...)) - a call that answers null for
     * as long as the sound exporter does not exist yet. An image decodes in a
     * moment and a video decoder takes a while, so whichever roll opened on a
     * still lost that race to whichever row opened on footage as soon as the
     * edit had enough layers: "Asset loader error" on every export with an
     * overlay. With the clock declaring sound it is the primary for both tracks;
     * as Media3's own gap it creates the sound exporter first and the picture's
     * second, in one go, and no other sequence can get past its own picture
     * before that - so by the time any of them asks for sound to be forced, the
     * exporter is there. It is the one order that cannot be lost.
     */
    fun sequenceTracks(layers: Layers, videoOut: Boolean, baseAudio: Boolean, heard: (Clip) -> Boolean): List<Tracks?> {
        val own = layers.layers.map { layer ->
            val rowHeard = layer.role == Role.Overlay && layer.clips.any(heard)
            when {
                !videoOut && layer.role != Role.Base && !rowHeard -> null
                !videoOut -> Tracks(video = false, sound = true)
                // The background is pictures alone, whatever the rest declare.
                layer.role == Role.Backdrop -> Tracks(video = true, sound = false)
                layer.role == Role.Base && baseAudio -> Tracks(video = true, sound = true)
                rowHeard -> Tracks(video = true, sound = true)
                else -> Tracks(video = true, sound = false)
            }
        }
        val anySound = own.any { it?.sound == true }
        return layers.layers.zip(own) { layer, tracks ->
            if (layer.role == Role.Clock && tracks != null) tracks.copy(sound = anySound) else tracks
        }
    }

    // ---- Transitions ---------------------------------------------------------

    /**
     * How one clip is drawn at a moment.
     *
     * [alpha] is its opacity. [shiftX] and [shiftY] move the picture right and
     * down by that fraction of the frame, uncovering nothing behind it. [scale]
     * grows it about its centre. Only the part of the frame from [keepFrom] to
     * [keepTo] across, and [keepFromY] to [keepToY] down (fractions of the
     * frame), is drawn at all. [white] mixes what is drawn towards white.
     * [blur] softens it: how far apart the nine taps are taken, as a fraction
     * of the frame, so the softness is the same at any resolution - the same
     * number and the same nine taps the effects library's Blur uses.
     */
    data class Draw(
        val alpha: Float = 1f,
        val shiftX: Float = 0f,
        val keepFrom: Float = 0f,
        val keepTo: Float = 1f,
        val shiftY: Float = 0f,
        val scale: Float = 1f,
        val keepFromY: Float = 0f,
        val keepToY: Float = 1f,
        val white: Float = 0f,
        val blur: Float = 0f
    ) {
        val isPlain: Boolean
            get() = alpha >= 1f && shiftX == 0f && shiftY == 0f && scale == 1f && white == 0f && blur == 0f &&
                keepFrom <= 0f && keepTo >= 1f && keepFromY <= 0f && keepToY >= 1f

        /** This draw at [factor] of its opacity: a clip's own fade under its transition. */
        fun faded(factor: Float): Draw = if (factor >= 1f) this else copy(alpha = alpha * factor.coerceIn(0f, 1f))

        /** This draw on top of [own]: the transition's, then the clip's own laid on it - both fade, and an overlay's own arrival supplies the movement. */
        fun over(own: Draw): Draw = Draw(
            alpha = alpha * own.alpha,
            shiftX = shiftX + own.shiftX,
            shiftY = shiftY + own.shiftY,
            scale = scale * own.scale,
            keepFrom = maxOf(keepFrom, own.keepFrom),
            keepTo = minOf(keepTo, own.keepTo),
            keepFromY = maxOf(keepFromY, own.keepFromY),
            keepToY = minOf(keepToY, own.keepToY),
            white = maxOf(white, own.white),
            // The wider of the two rather than the sum: two softenings of the
            // same picture are one softening, and adding them would take a
            // defocus join on a clip that is also being blurred past the reach
            // the nine taps are shaped for.
            blur = maxOf(blur, own.blur)
        )

        /**
         * The numbers the transition shader is given, in its own space. The
         * draw's Y runs down the picture, as the preview's and the sheet's do;
         * the frame texture's Y runs up it (the same NDC-is-up fact
         * [placementMatrix] negates). So the shift is turned over and the kept
         * band mirrored: [shiftY] is the picture moved down, and down in the
         * texture is minus. Every vertical transition was mirrored between the
         * preview and the file before this was one function checked against the
         * preview's pixel (ExportPlanChecks.shaderPixel).
         */
        fun shaderUniforms(): ShaderUniforms = ShaderUniforms(
            alpha = alpha.coerceIn(0f, 1f),
            shiftX = shiftX,
            shiftY = -shiftY,
            scale = scale,
            keepFromX = keepFrom,
            keepFromY = 1f - keepToY,
            keepToX = keepTo,
            keepToY = 1f - keepFromY,
            white = white.coerceIn(0f, 1f),
            // Symmetric about the pixel, so it needs no turning over.
            blur = blur.coerceIn(0f, MAX_BLUR)
        )
    }

    /** [Draw.shaderUniforms]: what goes into uAlpha, uShift, uScale, uKeep, uWhite and uBlur, texture-space Y. */
    data class ShaderUniforms(
        val alpha: Float,
        val shiftX: Float,
        val shiftY: Float,
        val scale: Float,
        val keepFromX: Float,
        val keepFromY: Float,
        val keepToX: Float,
        val keepToY: Float,
        val white: Float,
        val blur: Float
    )

    /**
     * As far apart as the nine taps are ever taken, anywhere in the app.
     *
     * The number is the effects library's own Blur at full: `0.012 * twice`
     * with `twice` reaching 1.75 (TimedEffect.FxParams.at). That is the widest
     * ring that has ever been shipped, so it is the one the rest is held to -
     * a Defocus join peaks at 0.010, well inside it. It was written here as
     * 0.014 on the day the join was built, which was a guess and was *under*
     * what the library was already asking for, so the comment claiming a
     * ceiling described something nothing enforced.
     *
     * It is a ceiling because a box of nine stops reading as a softening and
     * starts reading as nine copies somewhere past here - which is the open
     * question in docs/DEVICE_FINDINGS.md, and the one a phone settles.
     */
    const val MAX_BLUR = 0.021f

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

    /**
     * (incoming, outgoing) for a transition [p] of the way through.
     *
     * The preview draws the incoming shot over the outgoing one and asks with
     * [incomingOnTop] true; the export asks with whichever way its rolls fell.
     * Every kind here composites to the same picture either way - the
     * ExportPlanChecks hold it to that - so which roll a shot lands on, which is
     * an accident of the edit, never shows.
     */
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

        // The same through white: the shot showing is mixed towards white.
        TransitionType.DipToWhite ->
            if (p < 0.5f) HIDDEN to Draw(white = 2f * p)
            else Draw(white = 2f - 2f * p) to HIDDEN

        TransitionType.SlideLeft ->
            if (incomingOnTop) Draw(shiftX = 1f - p) to PLAIN
            // The old shot stays put and is cut away where the new one has arrived.
            else Draw(shiftX = 1f - p) to Draw(keepTo = 1f - p)

        TransitionType.SlideRight ->
            if (incomingOnTop) Draw(shiftX = -(1f - p)) to PLAIN
            else Draw(shiftX = -(1f - p)) to Draw(keepFrom = p)

        // From below: shifted down by what is still to come.
        TransitionType.SlideUp ->
            if (incomingOnTop) Draw(shiftY = 1f - p) to PLAIN
            else Draw(shiftY = 1f - p) to Draw(keepToY = 1f - p)

        TransitionType.SlideDown ->
            if (incomingOnTop) Draw(shiftY = -(1f - p)) to PLAIN
            else Draw(shiftY = -(1f - p)) to Draw(keepFromY = p)

        // Both move, and never overlap: the new shot takes the room the old one leaves.
        TransitionType.Push -> Draw(shiftX = 1f - p) to Draw(shiftX = -p)

        TransitionType.WipeRight ->
            if (incomingOnTop) Draw(keepTo = p) to PLAIN
            else PLAIN to Draw(keepFrom = p)

        TransitionType.WipeLeft ->
            if (incomingOnTop) Draw(keepFrom = 1f - p) to PLAIN
            else PLAIN to Draw(keepTo = 1f - p)

        // The new shot lands from larger. Zoomed in it covers the whole frame,
        // so under the old one it can be plain and the old one fades instead.
        TransitionType.ZoomIn -> {
            val landing = 1f + ZOOM_FROM * (1f - p)
            if (incomingOnTop) Draw(alpha = p, scale = landing) to PLAIN
            else Draw(scale = landing) to Draw(alpha = 1f - p)
        }

        // A hard cut, the old shot shaken harder up to it and the new one
        // shaken less and less after it. One shot showing at a time.
        TransitionType.Jitter ->
            if (p < 0.5f) HIDDEN to jitter(p, strength = 2f * p)
            else jitter(p, strength = 2f - 2f * p) to HIDDEN

        // The two alternate, and the new one holds from the last swap.
        TransitionType.Flicker ->
            if (flickerShowsIncoming(p)) PLAIN to HIDDEN else HIDDEN to PLAIN

        // White bursting on the cut and dying away after it.
        TransitionType.Flash ->
            if (p < 0.5f) HIDDEN to Draw(white = smoothstep(0.3f, 0.5f, p))
            else Draw(white = 1f - smoothstep(0.5f, 0.75f, p)) to HIDDEN

        // A dissolve that brightens through its middle: both shots whitened
        // the same, so the mix reads the same whichever is on top.
        TransitionType.Glow -> {
            val glow = GLOW_PEAK * (1f - kotlin.math.abs(2f * p - 1f))
            if (incomingOnTop) Draw(alpha = p, white = glow) to Draw(white = glow)
            else Draw(white = glow) to Draw(alpha = 1f - p, white = glow)
        }

        TransitionType.PushRight -> Draw(shiftX = -(1f - p)) to Draw(shiftX = p)
        TransitionType.PushUp -> Draw(shiftY = 1f - p) to Draw(shiftY = -p)
        TransitionType.PushDown -> Draw(shiftY = -(1f - p)) to Draw(shiftY = p)

        // A push on a steep curve: most of the travel in the middle third.
        TransitionType.Whip -> {
            val e = whip(p)
            Draw(shiftX = 1f - e) to Draw(shiftX = -e)
        }

        // The new shot rises into view from the bottom edge.
        TransitionType.WipeUp ->
            if (incomingOnTop) Draw(keepFromY = 1f - p) to PLAIN
            else PLAIN to Draw(keepToY = 1f - p)

        TransitionType.WipeDown ->
            if (incomingOnTop) Draw(keepToY = p) to PLAIN
            else PLAIN to Draw(keepFromY = p)

        // The old shot grows past the frame and fades off the new one under it.
        TransitionType.ZoomOut -> {
            val growing = 1f + ZOOM_FROM * p
            if (incomingOnTop) Draw(alpha = p) to Draw(scale = growing)
            else PLAIN to Draw(alpha = 1f - p, scale = growing)
        }

        // The old shot fades out, then the new one grows out of the middle.
        // One shot at a time, like the dips: grown over the old one, the
        // picture depended on which roll it was on (a shrunk shot under the
        // old one cannot be cut a hole for), and the preview stacks one way.
        TransitionType.PopIn ->
            if (p < 0.5f) HIDDEN to Draw(alpha = 1f - 2f * p)
            else {
                val q = 2f * p - 1f
                Draw(alpha = minOf(1f, 3f * q), scale = POP_FROM + (1f - POP_FROM) * smoothstep(0f, 1f, q)) to HIDDEN
            }

        // A focus pull through the cut: both shots softened by the same amount,
        // most at the middle, dissolving across. Both the same, like the Glow,
        // so the mix reads alike whichever shot is on top.
        TransitionType.Defocus -> {
            val soft = BLUR_PEAK * (1f - kotlin.math.abs(2f * p - 1f))
            if (incomingOnTop) Draw(alpha = p, blur = soft) to Draw(blur = soft)
            else Draw(blur = soft) to Draw(alpha = 1f - p, blur = soft)
        }

        // The old shot burns to white over the new one and thins away. The new
        // shot is whole underneath from the first frame; what fades is the white
        // ghost of the old one. Zero at p=0, so the overlap still opens on the
        // old shot alone.
        TransitionType.BurnOut -> {
            val burn = smoothstep(0f, BURN_IN, p)
            if (incomingOnTop) Draw(alpha = p) to Draw(white = burn)
            else PLAIN to Draw(alpha = 1f - p, white = burn)
        }

        // Black for the moments either side of the cut, hard in and out.
        TransitionType.Blackout ->
            if (p < 0.5f) HIDDEN to Draw(alpha = 1f - smoothstep(0.3f, 0.45f, p))
            else Draw(alpha = smoothstep(0.55f, 0.7f, p)) to HIDDEN
    }

    /** 0 to 1, slow at both ends and fast through the middle: a whip pan's travel. */
    private fun whip(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return if (x < 0.5f) 0.5f * Math.pow((2f * x).toDouble(), 3.0).toFloat()
        else 1f - 0.5f * Math.pow((2f - 2f * x).toDouble(), 3.0).toFloat()
    }

    /** How small the Pop in transition's new shot starts. */
    private const val POP_FROM = 0.3f

    /** How much larger than the frame the Zoom transition's new shot starts. */
    private const val ZOOM_FROM = 0.6f

    /** How far towards white a Glow goes at its middle. */
    private const val GLOW_PEAK = 0.7f

    /** How soft a Defocus is at its middle: the nine taps' reach, a fraction of the frame. */
    private const val BLUR_PEAK = 0.010f

    /** How much of a Burn out is spent getting the old shot to white. */
    private const val BURN_IN = 0.35f

    /** Whether the Flicker shows the new shot at [p]: every other beat, and always at the end. */
    fun flickerShowsIncoming(p: Float): Boolean {
        if (p >= 1f) return true
        val beat = (p * FLICKER_BEATS).toInt()
        return beat % 2 == 1
    }

    /** An even number, so the last beat before the end is the old shot and the end is a change. */
    private const val FLICKER_BEATS = 8

    /**
     * A shake at [strength] (0..1) for the Jitter cut: a few percent of the
     * frame either way, from sines at unrelated rates so it does not repeat,
     * and the same for the preview and the file because it is a function of
     * [p] alone.
     */
    private fun jitter(p: Float, strength: Float): Draw {
        val s = strength.coerceIn(0f, 1f)
        val x = kotlin.math.sin(p * 97.0).toFloat() * 0.6f + kotlin.math.sin(p * 41.0 + 1.3).toFloat() * 0.4f
        val y = kotlin.math.sin(p * 83.0 + 0.7).toFloat() * 0.6f + kotlin.math.sin(p * 59.0 + 2.1).toFloat() * 0.4f
        return Draw(shiftX = JITTER_REACH * s * x, shiftY = JITTER_REACH * s * y)
    }

    private const val JITTER_REACH = 0.05f

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- A clip's own draw ---------------------------------------------------------

    /**
     * How a clip fades itself at [playedMs] into it, whatever the transition
     * around it: its opacity track, its arrival's or leaving's fade, and - on
     * an overlay with a transition set - the transition as its [arrival],
     * drawn over the head of the clip on its own. An overlay has no shot under
     * it on its row to blend with, so its transition is its arrival, from the
     * same blend the main track draws.
     */
    fun ownDrawAt(clip: Clip, playedMs: Long): Draw {
        val alpha = clip.alphaAt(playedMs)
        val transition = clip.transitionIn
        if (!clip.isOverlay || !transition.isActive) return PLAIN.faded(alpha)
        val length = overlayTransitionMs(clip)
        if (length <= 0L || playedMs >= length) return PLAIN.faded(alpha)
        val p = (playedMs.toFloat() / length).coerceIn(0f, 1f)
        return arrival(transition.type, p).faded(alpha)
    }

    /**
     * A transition as one shot's arrival, [p] of the way in: the incoming half
     * of [blend], over whatever is beneath. The kinds that show one shot at a
     * time - the dips, the jitter, the flash, the flicker - hide the incoming
     * shot for their whole first half, since the old shot has the screen then;
     * with no old shot to hand it to, that half is a hole. Those run their
     * second half over the whole arrival instead: a dip to black is a fade up,
     * a dip to white opens on white and settles, a flash dies away from the
     * first frame, a jitter shakes itself still.
     */
    fun arrival(type: TransitionType, p: Float): Draw {
        // A Burn out's whole character is on the shot that is leaving, and an
        // arrival has none. Taken from blend like the rest it would come out a
        // plain dissolve - the one kind on the sheet that did nothing when it
        // was picked on an overlay. The overlay arrives out of the white
        // instead: the same white, on the shot that is here.
        if (type == TransitionType.BurnOut) {
            val q = p.coerceIn(0f, 1f)
            return Draw(alpha = q, white = 1f - q)
        }
        val q = if (type in ONE_AT_A_TIME) 0.5f + p.coerceIn(0f, 1f) / 2f else p
        return blend(type, q, incomingOnTop = true).first
    }

    private val ONE_AT_A_TIME = setOf(
        TransitionType.DipToBlack, TransitionType.DipToWhite, TransitionType.Jitter,
        TransitionType.Flash, TransitionType.Flicker, TransitionType.PopIn, TransitionType.Blackout
    )

    /** Whether [clip], an overlay, has another overlay on its row ending where it starts: the model's rule (Clip.hasOverlayJoin). */
    fun hasOverlayJoin(clip: Clip, others: List<Clip>): Boolean = clip.hasOverlayJoin(others)

    /** How long an overlay's transition runs over its head: what was asked for, within the clip. */
    fun overlayTransitionMs(clip: Clip): Long =
        if (!clip.transitionIn.isActive) 0L else clip.transitionIn.durationMs.coerceAtMost(clip.durationMs)

    /**
     * Whether a clip's own draw is ever anything but plain, so the export gives
     * it a per-frame pass only when one is needed: a picture-in-picture at 60%,
     * an opacity key, a fade in, a transition on an overlay.
     */
    fun drawsOwn(clip: Clip): Boolean =
        clip.fadesPicture || (clip.isOverlay && clip.transitionIn.isActive)

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

    // ---- A clip's own turn ------------------------------------------------------------

    /**
     * A clip's quarter turns as Media3's rotation: [quarterTurns] clockwise as
     * seen, and ScaleAndRotateTransformation turns counterclockwise for positive
     * degrees, so one turn is 270. The preview's view turns by
     * [screenTurnDegrees], clockwise, the same number of quarters.
     */
    fun turnDegrees(quarterTurns: Int): Float = ((4 - (quarterTurns % 4 + 4) % 4) % 4 * 90).toFloat()

    /** The same turn as a view's rotationZ, which runs clockwise. */
    fun screenTurnDegrees(quarterTurns: Int): Float = (((quarterTurns % 4 + 4) % 4) * 90).toFloat()

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
            val played = playedMs(clip, sourceElapsedMs)
            // The arrival, leaving and loop over the keys, as the preview draws
            // them (Clip.transformAt): the same played clock, the same order.
            clip.keyframes.transformAt(played, clip.staticTransform).animated(clip.animationFrame(played))
        }
    }

    /** How far into the played clip a frame [sourceElapsedMs] into its window is. */
    fun playedMs(clip: Clip, sourceElapsedMs: Long): Long =
        if (clip.speedRamp.isIdentity) sourceElapsedMs
        else clip.speedRamp.outputOffsetAt(sourceElapsedMs, clip.sourceSpanMs)

    fun hasMotion(clip: Clip, part: MotionPart): Boolean = when (part) {
        MotionPart.Stabilizer -> clip.stabilizer.isNotEmpty()
        MotionPart.User -> clip.keyframes.isNotEmpty() || !clip.staticTransform.isIdentity || clip.hasAnimation
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

    /**
     * The other way round: a point of the finished frame to the point of the
     * layer that lands on it, both in 0..1 with y down - which is what a shader
     * sampling the layer needs, and the inverse of what [placementMatrix] draws.
     *
     * Returned as a 3x3 in **column-major** order, the way GLSL reads a `mat3`.
     * A point outside 0..1 came from outside the layer, so the shader leaves the
     * picture alone there.
     *
     * Checked by round trip: a point put through the placement and back through
     * this comes out where it started (LayerBlendChecks), which ties it to the
     * placement the overlay box on screen is already known to agree with.
     */
    fun layerLookup(t: Transform, layerAspect: Float?, frameW: Float, frameH: Float): FloatArray {
        val m = placementMatrix(t, if (frameH > 0f) frameW / frameH else 1f)
        // The layer fitted to the frame, as half-extents in NDC.
        val fw = fittedHalfWidth(layerAspect, frameW, frameH)
        val fh = fittedHalfHeight(layerAspect, frameW, frameH)

        // frame uv -> frame ndc
        // ndc = (2u - 1, 1 - 2v)
        // layer ndc = inverse(A) * (ndc - translation), A = [[m0, m1], [m2, m3]]
        val det = m[0] * m[3] - m[1] * m[2]
        val d = if (kotlin.math.abs(det) < 1e-9f) 1e-9f else det
        val i0 = m[3] / d
        val i1 = -m[1] / d
        val i2 = -m[2] / d
        val i3 = m[0] / d
        val tx = m[4]
        val ty = m[5]

        // layer ndc -> layer uv: lu = (lx + fw) / (2 fw), lv = (fh - ly) / (2 fh)
        val su = 1f / (2f * fw)
        val sv = -1f / (2f * fh)

        // Folded: uv -> ndc -> layer ndc -> layer uv, as one affine map.
        // ndc.x = 2u - 1, ndc.y = -2v + 1
        val a00 = su * i0 * 2f
        val a01 = su * i1 * -2f
        val a02 = su * (i0 * (-1f - tx) + i1 * (1f - ty)) + 0.5f
        val a10 = sv * i2 * 2f
        val a11 = sv * i3 * -2f
        val a12 = sv * (i2 * (-1f - tx) + i3 * (1f - ty)) + 0.5f

        // Column-major for GLSL: columns are (a00, a10, 0), (a01, a11, 0), (a02, a12, 1).
        return floatArrayOf(a00, a10, 0f, a01, a11, 0f, a02, a12, 1f)
    }

    /** Half the width the layer takes in NDC once fitted - see OverlayRules.fitted. */
    private fun fittedHalfWidth(layerAspect: Float?, frameW: Float, frameH: Float): Float {
        if (layerAspect == null || !layerAspect.isFinite() || layerAspect <= 0f) return 1f
        return if (layerAspect > frameW / frameH) 1f else (frameH * layerAspect) / frameW
    }

    private fun fittedHalfHeight(layerAspect: Float?, frameW: Float, frameH: Float): Float {
        if (layerAspect == null || !layerAspect.isFinite() || layerAspect <= 0f) return 1f
        return if (layerAspect > frameW / frameH) (frameW / layerAspect) / frameH else 1f
    }

    // ---- Sound -----------------------------------------------------------------

    /** What a composited export mixes at when nothing in it says: the rate every phone records at. */
    const val DEFAULT_SAMPLE_RATE_HZ = 48_000

    /**
     * The rate the mixer runs at, from the [sampleRatesHz] of every sound in the
     * edit that is known (zero or less for one that is not).
     *
     * Media3 mixes every sound at the rate of the first input it is handed, and
     * in a composited export that is the clock's gap, whose fixed format is
     * 44.1 kHz - so a 48 kHz camera track under an overlay was resampled down
     * on the way to the file while the same footage cut end to end kept its
     * rate. The clock's gap carries a resampler to this rate instead
     * (CompositionFactory.clockGap): the highest rate any sound has, so nothing
     * is stepped down, and [DEFAULT_SAMPLE_RATE_HZ] when none is known. Within
     * what an AAC encoder takes.
     */
    fun mixerSampleRate(sampleRatesHz: Iterable<Int>): Int =
        (sampleRatesHz.filter { it > 0 }.maxOrNull() ?: DEFAULT_SAMPLE_RATE_HZ).coerceIn(MIN_SAMPLE_RATE_HZ, MAX_SAMPLE_RATE_HZ)

    const val MIN_SAMPLE_RATE_HZ = 8_000
    const val MAX_SAMPLE_RATE_HZ = 96_000

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

    /** The most channels an input has a *layout* for; eight is 7.1. */
    const val MAX_INPUT_CHANNELS = 8

    /**
     * The most channels a matrix is made for at all.
     *
     * Past [MAX_INPUT_CHANNELS] there is no layout anyone agrees on, but a file
     * that has one still has to play and still has to export: a channel count
     * with no matrix makes `ChannelMixingAudioProcessor.onConfigure` throw
     * ("No mixing matrix set for input channel count"), which in the export is
     * a failed render and in the preview a sound that will not start. AAC
     * allows up to forty-eight; this is past anything a phone will decode.
     */
    const val MAX_ANY_CHANNELS = 48

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
        val n = inputChannels.coerceIn(1, MAX_ANY_CHANNELS)
        if (n <= 2) return FloatArray(n * n) { if (it / n == it % n) 1f else 0f }
        if (n > MAX_INPUT_CHANNELS) {
            // No layout past 7.1 that anyone agrees on, so the first two
            // channels are taken as the stereo pair and the rest dropped -
            // which is what a player does with a layout it does not know, and
            // the alternative is not a worse mix but no mix: a channel count
            // with no matrix makes the processor throw.
            return FloatArray(n * 2) { if (it == 0 || it == 3) 1f else 0f }
        }
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
