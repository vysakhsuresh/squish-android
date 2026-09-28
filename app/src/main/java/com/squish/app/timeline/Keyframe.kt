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
