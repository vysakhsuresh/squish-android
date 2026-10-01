package com.squish.app.timeline

/**
 * How a value travels from one key to the next. The easing belongs to the key the
 * segment leaves, which is the convention every editor uses - you set how a key
 * exits, not how the next one arrives.
 */
enum class KeyframeEasing(val label: String) {
    /** Eases out and back in. The default, because constant-velocity motion on a
     *  picture reads as mechanical and is almost never what anyone wants. */
    Smooth("Smooth"),
    Linear("Linear"),
    /** Holds this value until the next key, then jumps. Step animation. */
    Hold("Hold");

    fun ease(t: Float): Float = when (this) {
        Linear -> t
        Smooth -> t * t * (3f - 2f * t)
        Hold -> 0f
    }
}

/**
 * Where a clip's picture sits: scaled, moved and turned.
 *
 * Offsets are fractions of half the canvas, so ±1 puts the center of the picture
 * on the edge. That is the same convention the export's placement matrix uses, and
 * keeping one convention is what lets the preview and the render agree.
 */
data class Transform(
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val rotationDegrees: Float = 0f
) {
    val isIdentity: Boolean
        get() = scale == 1f && offsetXFraction == 0f && offsetYFraction == 0f && rotationDegrees == 0f

    companion object {
        val Identity = Transform()

        fun lerp(a: Transform, b: Transform, t: Float): Transform = Transform(
            scale = a.scale + (b.scale - a.scale) * t,
            offsetXFraction = a.offsetXFraction + (b.offsetXFraction - a.offsetXFraction) * t,
            offsetYFraction = a.offsetYFraction + (b.offsetYFraction - a.offsetYFraction) * t,
            rotationDegrees = a.rotationDegrees + (b.rotationDegrees - a.rotationDegrees) * t
        )
    }
}

/**
 * One control point on a clip's animation.
 *
 * [atMs] is measured from the start of the clip *on the timeline*, not from the
 * start of the source file. Move the clip and the animation travels with it, which
 * is what "this shot pushes in over its three seconds" means to an editor.
 */
data class Keyframe(
    val atMs: Long,
    val transform: Transform = Transform.Identity,
    val easing: KeyframeEasing = KeyframeEasing.Smooth
)

/**
 * The transform at a moment inside the clip.
 *
 * The list is required to be sorted by [Keyframe.atMs]; the editor keeps it that
 * way on every insert. This runs once per frame on the render thread, so it does
 * no sorting and allocates nothing beyond the result.
 *
 * Outside the first and last key the animation holds rather than extrapolating -
 * a value that keeps racing past the last key you set is never what you meant.
 */
fun List<Keyframe>.transformAt(tInClipMs: Long, fallback: Transform): Transform {
    if (isEmpty()) return fallback
    val first = this[0]
    if (tInClipMs <= first.atMs) return first.transform
    val last = this[size - 1]
    if (tInClipMs >= last.atMs) return last.transform

    var i = 0
    while (i < size - 1 && this[i + 1].atMs <= tInClipMs) i++
    val a = this[i]
    val b = this[i + 1]

    val span = (b.atMs - a.atMs).coerceAtLeast(1L)
    val raw = ((tInClipMs - a.atMs).toFloat() / span).coerceIn(0f, 1f)
    return Transform.lerp(a.transform, b.transform, a.easing.ease(raw))
}

/**
 * A clip's placement at a moment, with stabilization folded in.
 *
 * The two tracks are kept apart on purpose. Stabilization is measured from the
 * footage; the keyframes are what the editor asked for. Writing the correction into
 * the same track would mean re-analyzing every time someone nudged a slider, and
 * applying a preset would silently throw the stabilization away.
 *
 * [stabilizerMs] is source time, not clip time: the correction belongs to a frame of
 * the file, so trimming the head of a clip must not slide the whole correction out
 * of step with the picture it was measured from.
 */
fun composeTransform(
    keyframes: List<Keyframe>,
    staticTransform: Transform,
    stabilizer: List<Keyframe>,
    localMs: Long,
    stabilizerMs: Long
): Transform {
    val user = keyframes.transformAt(localMs, staticTransform)
    if (stabilizer.isEmpty()) return user
    val fix = stabilizer.transformAt(stabilizerMs, Transform.Identity)
    return Transform(
        scale = user.scale * fix.scale,
        offsetXFraction = user.offsetXFraction + fix.offsetXFraction,
        offsetYFraction = user.offsetYFraction + fix.offsetYFraction,
        rotationDegrees = user.rotationDegrees + fix.rotationDegrees
    )
}

/**
 * The one set of limits on where a picture can be put.
 *
 * There used to be four: the Blend sliders stopped at 1x, the model clamped to 2x,
 * Motion's slider stopped at 3x and the view model clamped to 4x, with offsets of
 * ±1 in one place and ±1.5 in another. A value set in one panel was silently cut
 * down by the next one to touch the clip.
 */
object TransformLimits {
    const val SCALE_MIN = 0.1f
    const val SCALE_MAX = 4f
    /** Half a canvas past the edge, so a picture can be slid almost out of frame. */
    const val OFFSET_MAX = 1.5f
    const val ROTATION_MAX = 180f
}

fun Transform.clamped(): Transform = Transform(
    scale = scale.coerceIn(TransformLimits.SCALE_MIN, TransformLimits.SCALE_MAX),
    offsetXFraction = offsetXFraction.coerceIn(-TransformLimits.OFFSET_MAX, TransformLimits.OFFSET_MAX),
    offsetYFraction = offsetYFraction.coerceIn(-TransformLimits.OFFSET_MAX, TransformLimits.OFFSET_MAX),
    rotationDegrees = rotationDegrees.coerceIn(-TransformLimits.ROTATION_MAX, TransformLimits.ROTATION_MAX)
)

/**
 * The same animation with every key [deltaMs] later (earlier when negative).
 *
 * Keys are measured from the clip's head, so any edit that moves the head - a
 * trim, or the second half of a cut - has to move them with it, or the move plays
 * against different frames than the ones it was drawn on. A push-in cut in half
 * used to restart from scale 1 on the second half: a visible snap at the cut.
 *
 * Nothing is dropped or re-sampled. Keys that end up before 0 or past the clip's
 * end sit over footage the clip does not show, and still shape the part it does:
 * the frames either side of a cut, or inside a trim, play exactly the pose they
 * had, easing and all, and the move comes back whole when the footage does.
 * Evaluation holds before the first key and after the last wherever they are,
 * so a key outside the clip is never extrapolated past.
 */
fun List<Keyframe>.shiftedBy(deltaMs: Long): List<Keyframe> =
    if (deltaMs == 0L || isEmpty()) this else map { it.copy(atMs = it.atMs + deltaMs) }

/** The keys that fall on a clip [durationMs] long - the ones a panel can offer to edit. */
fun List<Keyframe>.within(durationMs: Long): List<Keyframe> = filter { it.atMs in 0L..durationMs }

/** The easing of the segment a moment falls in - the easing of the key it leaves. */
fun List<Keyframe>.easingAt(atMs: Long): KeyframeEasing =
    lastOrNull { it.atMs <= atMs }?.easing ?: firstOrNull()?.easing ?: KeyframeEasing.Smooth

/**
 * Inserts a key, or replaces the one already within [toleranceMs] of it. Two keys a
 * frame apart are a fight, not an animation.
 */
fun List<Keyframe>.upserted(key: Keyframe, toleranceMs: Long): List<Keyframe> =
    (filterNot { kotlin.math.abs(it.atMs - key.atMs) <= toleranceMs } + key).sortedBy { it.atMs }

// ---- One number over time --------------------------------------------------------

/**
 * One control point on a single number's track - a clip's opacity, a sound's
 * level. The same clock as [Keyframe]: [atMs] from the clip's start on the
 * timeline, so the track travels with the clip and a trim or a cut moves it
 * exactly as it moves the placement keys.
 *
 * Its own type rather than a field on [Keyframe], because the two tracks are
 * keyed at different moments: a fade of an overlay is two keys, a move across
 * the frame may be five, and a key on one must not invent a key on the other.
 */
data class ValueKey(
    val atMs: Long,
    val value: Float,
    val easing: KeyframeEasing = KeyframeEasing.Smooth
)

/**
 * The value at a moment inside the clip, holding outside the first and last key
 * exactly as [transformAt] does, and [fallback] with no keys at all. Runs per
 * tick and per frame, so it walks the sorted list and allocates nothing.
 */
fun List<ValueKey>.valueAt(tInClipMs: Long, fallback: Float): Float {
    if (isEmpty()) return fallback
    val first = this[0]
    if (tInClipMs <= first.atMs) return first.value
    val last = this[size - 1]
    if (tInClipMs >= last.atMs) return last.value

    var i = 0
    while (i < size - 1 && this[i + 1].atMs <= tInClipMs) i++
    val a = this[i]
    val b = this[i + 1]
    val span = (b.atMs - a.atMs).coerceAtLeast(1L)
    val raw = ((tInClipMs - a.atMs).toFloat() / span).coerceIn(0f, 1f)
    return a.value + (b.value - a.value) * a.easing.ease(raw)
}

/** The track with every key [deltaMs] later; see [shiftedBy] for why nothing is dropped. */
@JvmName("valueKeysShiftedBy")
fun List<ValueKey>.shiftedBy(deltaMs: Long): List<ValueKey> =
    if (deltaMs == 0L || isEmpty()) this else map { it.copy(atMs = it.atMs + deltaMs) }

/** The keys that fall on a clip [durationMs] long. */
@JvmName("valueKeysWithin")
fun List<ValueKey>.within(durationMs: Long): List<ValueKey> = filter { it.atMs in 0L..durationMs }

/** The easing of the segment a moment falls in. */
@JvmName("valueKeysEasingAt")
fun List<ValueKey>.easingAt(atMs: Long): KeyframeEasing =
    lastOrNull { it.atMs <= atMs }?.easing ?: firstOrNull()?.easing ?: KeyframeEasing.Smooth

/** Inserts a key, or replaces the one already within [toleranceMs] of it. */
@JvmName("valueKeysUpserted")
fun List<ValueKey>.upserted(key: ValueKey, toleranceMs: Long): List<ValueKey> =
    (filterNot { kotlin.math.abs(it.atMs - key.atMs) <= toleranceMs } + key).sortedBy { it.atMs }

/** Whether a key sits within [toleranceMs] of [atMs] - what a keyframe button lights up for. */
fun List<ValueKey>.hasKeyNear(atMs: Long, toleranceMs: Long): Boolean =
    any { kotlin.math.abs(it.atMs - atMs) <= toleranceMs }

@JvmName("keyframesHaveKeyNear")
fun List<Keyframe>.hasKeyNear(atMs: Long, toleranceMs: Long): Boolean =
    any { kotlin.math.abs(it.atMs - atMs) <= toleranceMs }

// ---- Arrivals, leavings and loops --------------------------------------------------

/**
 * How a clip's picture arrives. Layered over the keyframes rather than written
 * into them: an arrival is a stretch at the clip's head, and a move drawn across
 * the whole clip must survive it being switched on, changed and taken off again.
 */
enum class ClipArrival(val label: String) {
    None("None"),
    Fade("Fade"),
    /** Grows into place from smaller, fading up. */
    Zoom("Zoom in"),
    /** Settles into place from larger. */
    Shrink("Zoom out"),
    /** Comes in from the right, to the left. */
    SlideLeft("Slide left"),
    SlideRight("Slide right"),
    /** Rises into place from below. */
    SlideUp("Slide up"),
    SlideDown("Slide down"),
    /** Turns a quarter turn into place, growing. */
    Spin("Spin"),
    /** Grows in past full size and settles, like a sticker landing. */
    Pop("Pop"),
    /** Drops from above and bounces to rest. */
    Bounce("Bounce"),
    /** Falls in from above the frame and overshoots a little. */
    Drop("Drop in"),
    /** A fast slide in from the left that brakes hard. */
    Whip("Whip"),
    /** Swings in on a hinge and settles. */
    SwingIn("Swing in"),
    /** A whole turn, growing out of a point. */
    Twirl("Twirl"),
    /** Flickers on like a screen catching. */
    Blink("Blink")
}

/** How a clip's picture leaves. */
enum class ClipLeaving(val label: String) {
    None("None"),
    Fade("Fade"),
    /** Grows past full size as it fades. */
    Zoom("Zoom in"),
    Shrink("Zoom out"),
    /** Goes out to the left. */
    SlideLeft("Slide left"),
    SlideRight("Slide right"),
    /** Goes out over the top. */
    SlideUp("Slide up"),
    SlideDown("Slide down"),
    Spin("Spin"),
    /** Swells a little, then is gone. */
    Pop("Pop"),
    /** Falls out of the bottom, faster as it goes. */
    Drop("Drop out"),
    /** A fast slide out to the right. */
    Whip("Whip"),
    /** A whole turn, shrinking to a point. */
    Twirl("Twirl"),
    /** Shrinks and sinks as it fades. */
    Sink("Sink"),
    /** Flickers off. */
    Blink("Blink")
}

/** What a clip's picture does the whole time it is on screen. */
enum class ClipLoop(val label: String) {
    None("None"),
    /** Breathes a little bigger and back. */
    Pulse("Pulse"),
    /** Rocks a few degrees either way. */
    Swing("Swing"),
    /** Floats up and down. */
    Bob("Bob"),
    /** Flickers between full and part brightness, never to black. */
    Flicker("Flicker"),
    /** Creeps in and back out, slowly. */
    Drift("Drift"),
    /** A small, quick tremble. */
    Shake("Shake"),
    /** Two quick beats and a rest. */
    Heartbeat("Heartbeat"),
    /** Sways side to side. */
    Sway("Sway"),
    /** Circles slowly round its place. */
    Orbit("Orbit"),
    /** Turns right round, over and over. */
    Rotate("Rotate")
}

/**
 * One moment of a clip's arrival, leaving and loop, as changes to lay over its
 * placement: [scale] multiplies, [dx] and [dy] add in fractions of half the
 * canvas (the offset's own units; +y is down, as on screen), [tilt] adds
 * degrees, and [alpha] multiplies the opacity.
 */
data class AnimFrame(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val tilt: Float = 0f
) {
    val isStill: Boolean get() = alpha == 1f && scale == 1f && dx == 0f && dy == 0f && tilt == 0f

    companion object {
        val STILL = AnimFrame()
    }
}

/** A placement with an animation's moment laid over it. */
fun Transform.animated(frame: AnimFrame): Transform =
    if (frame.isStill) this
    else Transform(
        scale = scale * frame.scale,
        offsetXFraction = offsetXFraction + frame.dx,
        offsetYFraction = offsetYFraction + frame.dy,
        rotationDegrees = rotationDegrees + frame.tilt
    )

/**
 * The arrival, the leaving and the loop of a clip's picture, worked out
 * together - the same shape as TextAnimation, because a line of words and a
 * picture-in-picture are asked to arrive the same way.
 *
 * Each of the three has its own length; the arrival and the leaving are each
 * cut back to half the clip so the two never overlap, whatever the sliders say
 * on a short clip. A slide comes from one full frame away: far enough that a
 * picture at any placement within [TransformLimits] has cleared the canvas.
 */
object ClipAnimation {
    const val DEFAULT_IN_MS = 500L
    const val DEFAULT_OUT_MS = 500L
    const val DEFAULT_LOOP_MS = 1_200L
    const val MIN_MOTION_MS = 100L
    const val MAX_MOTION_MS = 3_000L
    const val MIN_LOOP_MS = 300L
    const val MAX_LOOP_MS = 4_000L

    /** How far off the canvas a slide starts or ends, in half-canvas units. */
    private const val OFF_CANVAS = 2f

    fun frameAt(
        arrival: ClipArrival,
        leaving: ClipLeaving,
        loop: ClipLoop,
        inMs: Long,
        outMs: Long,
        loopMs: Long,
        elapsedMs: Long,
        totalMs: Long
    ): AnimFrame {
        if (arrival == ClipArrival.None && leaving == ClipLeaving.None && loop == ClipLoop.None) return AnimFrame.STILL
        val total = totalMs.coerceAtLeast(1L)
        val half = (total / 2).coerceAtLeast(1L)
        val inWindow = inMs.coerceIn(1L, half).toFloat()
        val outWindow = outMs.coerceIn(1L, half).toFloat()
        val inT = (elapsedMs / inWindow).coerceIn(0f, 1f)
        val outT = ((total - elapsedMs) / outWindow).coerceIn(0f, 1f)

        val arriving = when (arrival) {
            ClipArrival.None -> AnimFrame.STILL
            ClipArrival.Fade -> AnimFrame(alpha = easeOut(inT))
            ClipArrival.Zoom -> AnimFrame(alpha = minOf(1f, inT * 2.5f), scale = 0.5f + 0.5f * easeOut(inT))
            ClipArrival.Shrink -> AnimFrame(alpha = minOf(1f, inT * 2.5f), scale = 1.5f - 0.5f * easeOut(inT))
            ClipArrival.SlideLeft -> AnimFrame(dx = OFF_CANVAS * (1f - easeOut(inT)))
            ClipArrival.SlideRight -> AnimFrame(dx = -OFF_CANVAS * (1f - easeOut(inT)))
            ClipArrival.SlideUp -> AnimFrame(dy = OFF_CANVAS * (1f - easeOut(inT)))
            ClipArrival.SlideDown -> AnimFrame(dy = -OFF_CANVAS * (1f - easeOut(inT)))
            ClipArrival.Spin -> AnimFrame(
                alpha = minOf(1f, inT * 2.5f),
                scale = 0.4f + 0.6f * easeOut(inT),
                tilt = -90f * (1f - easeOut(inT))
            )
            ClipArrival.Pop -> AnimFrame(alpha = minOf(1f, inT * 3f), scale = overshoot(inT))
            ClipArrival.Bounce -> AnimFrame(alpha = minOf(1f, inT * 4f), dy = -0.7f * bounce(inT))
            ClipArrival.Drop -> AnimFrame(dy = -OFF_CANVAS * (1f - backOut(inT)))
            ClipArrival.Whip -> AnimFrame(dx = -OFF_CANVAS * (1f - expoOut(inT)), scale = 1f + 0.08f * (1f - expoOut(inT)))
            ClipArrival.SwingIn -> AnimFrame(
                alpha = minOf(1f, inT * 3f),
                tilt = if (inT >= 1f) 0f else 28f * (1f - inT) * kotlin.math.cos(inT * 3.0 * Math.PI).toFloat()
            )
            ClipArrival.Twirl -> AnimFrame(
                alpha = minOf(1f, inT * 2.5f),
                scale = 0.2f + 0.8f * easeOut(inT),
                tilt = -360f * (1f - easeOut(inT))
            )
            ClipArrival.Blink -> AnimFrame(alpha = blink(inT))
        }
        val leavingNow = when (leaving) {
            ClipLeaving.None -> AnimFrame.STILL
            ClipLeaving.Fade -> AnimFrame(alpha = easeOut(outT))
            ClipLeaving.Zoom -> AnimFrame(alpha = easeOut(outT), scale = 1.5f - 0.5f * easeOut(outT))
            ClipLeaving.Shrink -> AnimFrame(alpha = easeOut(outT), scale = 0.5f + 0.5f * easeOut(outT))
            ClipLeaving.SlideLeft -> AnimFrame(dx = -OFF_CANVAS * (1f - easeOut(outT)))
            ClipLeaving.SlideRight -> AnimFrame(dx = OFF_CANVAS * (1f - easeOut(outT)))
            ClipLeaving.SlideUp -> AnimFrame(dy = -OFF_CANVAS * (1f - easeOut(outT)))
            ClipLeaving.SlideDown -> AnimFrame(dy = OFF_CANVAS * (1f - easeOut(outT)))
            ClipLeaving.Spin -> AnimFrame(
                alpha = easeOut(outT),
                scale = 0.4f + 0.6f * easeOut(outT),
                tilt = 90f * (1f - easeOut(outT))
            )
            // outT runs 1 to 0 over the leaving; g is how far gone it is.
            ClipLeaving.Pop -> (1f - outT).let { g ->
                AnimFrame(alpha = if (g < 0.6f) 1f else (1f - g) / 0.4f, scale = 1f + 0.18f * kotlin.math.sin(g * Math.PI * 0.8).toFloat() - 0.6f * g * g)
            }
            ClipLeaving.Drop -> (1f - outT).let { g -> AnimFrame(dy = OFF_CANVAS * g * g) }
            ClipLeaving.Whip -> (1f - outT).let { g -> AnimFrame(dx = OFF_CANVAS * g * g * g, scale = 1f + 0.08f * g) }
            ClipLeaving.Twirl -> AnimFrame(alpha = easeOut(outT), scale = 0.2f + 0.8f * easeOut(outT), tilt = 360f * (1f - easeOut(outT)))
            ClipLeaving.Sink -> (1f - easeOut(outT)).let { g -> AnimFrame(alpha = easeOut(outT), scale = 1f - 0.35f * g, dy = 0.35f * g) }
            ClipLeaving.Blink -> AnimFrame(alpha = blink(outT))
        }
        val phase = ((elapsedMs.toDouble() / loopMs.coerceAtLeast(1L)) % 1.0).toFloat()
        val wave = kotlin.math.sin(phase * 2.0 * Math.PI).toFloat()
        val looping = when (loop) {
            ClipLoop.None -> AnimFrame.STILL
            ClipLoop.Pulse -> AnimFrame(scale = 1f + 0.05f * wave)
            ClipLoop.Swing -> AnimFrame(tilt = 3f * wave)
            ClipLoop.Bob -> AnimFrame(dy = 0.03f * wave)
            // Two dips a period, never to black.
            ClipLoop.Flicker -> AnimFrame(alpha = 0.72f + 0.28f * kotlin.math.abs(kotlin.math.sin(phase * 4.0 * Math.PI).toFloat()))
            // A slow push in and back: one breath per period, from rest.
            ClipLoop.Drift -> AnimFrame(scale = 1f + 0.06f * (0.5f - 0.5f * kotlin.math.cos(phase * 2.0 * Math.PI).toFloat()))
            // Three unrelated frequencies a period: a tremble, not a wobble; 0 at rest.
            ClipLoop.Shake -> AnimFrame(
                dx = 0.012f * kotlin.math.sin(phase * 2.0 * Math.PI * 7).toFloat(),
                dy = 0.009f * kotlin.math.sin(phase * 2.0 * Math.PI * 11).toFloat(),
                tilt = 0.8f * kotlin.math.sin(phase * 2.0 * Math.PI * 5).toFloat()
            )
            ClipLoop.Heartbeat -> AnimFrame(scale = 1f + 0.07f * heartbeat(phase))
            ClipLoop.Sway -> AnimFrame(dx = 0.04f * wave)
            ClipLoop.Orbit -> AnimFrame(
                dx = 0.025f * kotlin.math.sin(phase * 2.0 * Math.PI).toFloat(),
                dy = 0.025f * (1f - kotlin.math.cos(phase * 2.0 * Math.PI).toFloat())
            )
            ClipLoop.Rotate -> AnimFrame(tilt = 360f * phase)
        }
        return AnimFrame(
            alpha = arriving.alpha * leavingNow.alpha * looping.alpha,
            scale = arriving.scale * leavingNow.scale * looping.scale,
            dx = arriving.dx + leavingNow.dx + looping.dx,
            dy = arriving.dy + leavingNow.dy + looping.dy,
            tilt = arriving.tilt + leavingNow.tilt + looping.tilt
        )
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /** Past 1 and back, ending exactly on 1: a landing. */
    internal fun overshoot(t: Float): Float {
        if (t >= 1f) return 1f
        val s = 1.70158f * 1.3f
        val u = t - 1f
        return (1f + (s + 1f) * u * u * u + s * u * u).coerceAtLeast(0.01f)
    }

    /** Like [overshoot] but from 0 to 1 for a distance: lands a little past and comes back. */
    private fun backOut(t: Float): Float {
        if (t >= 1f) return 1f
        val s = 1.70158f
        val u = t - 1f
        return 1f + (s + 1f) * u * u * u + s * u * u
    }

    /** Most of the way at once, then the last of it slowly: a whip. */
    private fun expoOut(t: Float): Float = if (t >= 1f) 1f else 1f - Math.pow(2.0, -10.0 * t).toFloat()

    /** Falls and settles with two shrinking bounces: 1 at the start, 0 at rest. */
    private fun bounce(t: Float): Float {
        if (t >= 1f) return 0f
        val fall = 1f - t
        return kotlin.math.abs(kotlin.math.cos(t * Math.PI * 2.5).toFloat()) * fall * fall
    }

    /** On and off a few times, getting steadier: 0 at the start, 1 at the end. */
    private fun blink(t: Float): Float {
        if (t >= 1f) return 1f
        if (t <= 0f) return 0f
        val on = (t * 7).toInt() % 2 == 1 || t > 0.75f
        return if (on) 0.4f + 0.6f * t else 0.1f * t
    }

    /** Two quick beats then a rest, over one period; 0 at rest. */
    private fun heartbeat(phase: Float): Float {
        fun beat(at: Float) = kotlin.math.exp(-((phase - at) * (phase - at)) / 0.0018f)
        return beat(0.12f) + 0.7f * beat(0.32f)
    }
}
