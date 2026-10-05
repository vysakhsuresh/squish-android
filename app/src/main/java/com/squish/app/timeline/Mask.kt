package com.squish.app.timeline

import com.squish.app.media.video.MotionTrack

/**
 * The shapes a mask can take. Each is a signed distance function in the shader,
 * which is what lets one uniform feather them all identically.
 */
enum class MaskShape(val label: String) {
    Rectangle("Rectangle"),
    Ellipse("Ellipse"),
    /** A half-plane: everything on one side of a line. The classic reveal. */
    Linear("Linear"),
    /** A band between two parallel lines - a letterbox slot you can turn. */
    Mirror("Mirror"),
    /** The two CapCut has that the four above do not: filled to the same box as the ellipse. */
    Heart("Heart"),
    Star("Star");

    /** Whether the shape has a width and a height of its own to set, rather than an edge or a band. */
    val hasBox: Boolean get() = this == Rectangle || this == Ellipse || this == Heart || this == Star
}

/**
 * What the shape does to what is inside it.
 *
 * [Cutout] is a compositing tool; the other two are privacy tools, and they behave
 * differently on purpose - obscuring leaves the frame intact and destroys only what
 * is inside the shape, because hiding a face must not also punch a hole in the
 * picture.
 */
enum class MaskMode(val label: String) {
    Cutout("Cut out"),
    Pixelate("Pixelate"),
    Blur("Blur")
}

/**
 * Restricts a clip to a shape.
 *
 * Sizes and the center are fractions of the **frame**, so width 1.0 spans the
 * whole picture and the center runs -1 to 1 from edge to edge. Rotation and the
 * corner radius are applied in pixel-isotropic space, so a turned rectangle stays
 * a rectangle on a 16:9 frame rather than shearing into a rhombus.
 *
 * [feather] is the width of the soft edge, in the same isotropic units, and
 * [inverted] punches the shape out instead of keeping it.
 */
data class Mask(
    val shape: MaskShape = MaskShape.Ellipse,
    val centerXFraction: Float = 0f,
    val centerYFraction: Float = 0f,
    val widthFraction: Float = 0.6f,
    val heightFraction: Float = 0.6f,
    val rotationDegrees: Float = 0f,
    val feather: Float = 0.04f,
    val cornerRadius: Float = 0f,
    val inverted: Boolean = false,
    val mode: MaskMode = MaskMode.Cutout,
    /** How coarse the pixelation, or how wide the blur. */
    val strength: Float = 0.5f,

    /**
     * Pins the shape to something moving, in **source** time. A face does not hold
     * still, so a privacy mask that cannot follow one is a mask you have to keyframe
     * by hand for every frame of the shot.
     */
    val track: MotionTrack? = null,

    /**
     * The shape keyed, in **source** time - the clock the track already uses, so
     * a trim or a move carries the keys with the footage without anything
     * having to shift them.
     *
     * The other half of keyframed filters: a mask already *moves* on a track,
     * which is a measurement of something in the picture, and this is the hand
     * drawn version - grow a circle over four seconds, slide a letterbox open.
     * A key holds a whole shape rather than one number, because the eight
     * numbers of a mask are one thing to the eye and keying them apart would
     * mean eight rows of diamonds for one circle.
     *
     * A key's own [Mask] never carries keys or a track of its own; [keyed]
     * strips them, and [at] returns a shape with none.
     */
    val keys: List<MaskKey> = emptyList()
) {
    /** Never zero: it is the width of a smoothstep band in the shader. */
    val safeFeather: Float get() = feather.coerceAtLeast(0.001f)

    /**
     * The corner radius cannot exceed half the shorter side, or the rounding
     * inverts and the rectangle turns inside out.
     */
    val safeCornerRadius: Float
        get() = cornerRadius.coerceIn(0f, minOf(widthFraction, heightFraction) / 2f)

    val shapeIndex: Float get() = shape.ordinal.toFloat()
    val modeIndex: Float get() = mode.ordinal.toFloat()

    /**
     * Texture-coordinate units, so both read as a fraction of the frame.
     *
     * The ranges are set by what it takes to actually hide a face, measured rather
     * than guessed: the first draft topped out at a 3% blur radius, through which a
     * face was still perfectly recognisable. A privacy blur has to approach the size
     * of the feature it is destroying, which is why this reaches 14%.
     */
    val pixelSize: Float get() = 0.015f + 0.075f * strength.coerceIn(0f, 1f)
    val blurRadius: Float get() = 0.015f + 0.125f * strength.coerceIn(0f, 1f)

    /**
     * The shape's center at a moment of the source, following its track if it has
     * one. Track fractions run 0 to 1 across the frame; the shader's center runs
     * -1 to 1 from the middle.
     *
     * And the two run opposite ways up. `TrackSample.yFraction` is a row of a
     * bitmap over its height, so 0 is the top; the shader's y comes from
     * `vTexSamplingCoord`, a GL texture coordinate, so +1 is the top - which is
     * also the convention `centerYFraction` is held in, and what
     * `MaskOutline.dragged` returns `-2 * dy / h` for. Without the negation a
     * pinned track sat mirrored about the middle of the frame and walked up
     * while the thing it was following walked down, in the preview and in the
     * file alike.
     */
    fun centerAt(sourceMs: Long): Pair<Float, Float> {
        val sample = track?.sampleAt(sourceMs)
            ?: return centerXFraction to centerYFraction
        return (sample.xFraction - 0.5f) * 2f to -(sample.yFraction - 0.5f) * 2f
    }

    /** Whether the shape itself is keyed, rather than standing still or following a track. */
    val isKeyed: Boolean get() = keys.size >= 1

    /**
     * The shape at a moment of the source.
     *
     * Before the first key and after the last, that key's shape, exactly as
     * every other track here holds outside its ends. The numbers are mixed; the
     * shape, the mode and the inversion are taken from the key at or before the
     * moment, because there is no half way between a heart and a star.
     *
     * The result carries no keys of its own, so it can be handed straight to
     * the shader loader without it having to know any of this.
     */
    fun at(sourceMs: Long): Mask {
        if (keys.isEmpty()) return this
        val first = keys.first()
        if (keys.size == 1 || sourceMs <= first.atMs) return first.mask.inheriting(this)
        val last = keys.last()
        if (sourceMs >= last.atMs) return last.mask.inheriting(this)

        var i = 0
        while (i < keys.size - 1 && keys[i + 1].atMs <= sourceMs) i++
        val a = keys[i]
        val b = keys[i + 1]
        val span = (b.atMs - a.atMs).coerceAtLeast(1L)
        val t = a.easing.ease(((sourceMs - a.atMs).toFloat() / span).coerceIn(0f, 1f))
        return a.mask.mixedWith(b.mask, t).inheriting(this)
    }

    /** This shape as a key holds one: no keys and no track of its own. */
    fun keyed(): Mask = if (keys.isEmpty() && track == null) this else copy(keys = emptyList(), track = null)

    /** Whatever a key cannot hold, taken back from the mask the keys belong to. */
    private fun inheriting(owner: Mask): Mask = copy(keys = emptyList(), track = owner.track)

    /** This shape [t] of the way to [other]; the discrete fields stay this one's. */
    private fun mixedWith(other: Mask, t: Float): Mask = copy(
        centerXFraction = mix(centerXFraction, other.centerXFraction, t),
        centerYFraction = mix(centerYFraction, other.centerYFraction, t),
        widthFraction = mix(widthFraction, other.widthFraction, t),
        heightFraction = mix(heightFraction, other.heightFraction, t),
        // The short way round, so a shape turned from 350° to 10° goes forward
        // twenty degrees rather than backwards three hundred and forty.
        rotationDegrees = rotationDegrees + shortestTurn(rotationDegrees, other.rotationDegrees) * t,
        feather = mix(feather, other.feather, t),
        cornerRadius = mix(cornerRadius, other.cornerRadius, t),
        strength = mix(strength, other.strength, t)
    )

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun shortestTurn(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}

/** One moment of a keyed shape: [atMs] is source time, as the track's samples are. */
data class MaskKey(
    val atMs: Long,
    val mask: Mask,
    val easing: KeyframeEasing = KeyframeEasing.Smooth
)

/** Inserts a shape key, or replaces the one already within [toleranceMs] of it. */
fun List<MaskKey>.upserted(key: MaskKey, toleranceMs: Long = KEY_TOLERANCE_MS): List<MaskKey> =
    (filterNot { kotlin.math.abs(it.atMs - key.atMs) <= toleranceMs } + key).sortedBy { it.atMs }

/** Whether a shape key sits within [toleranceMs] of [atMs] - what the keyframe button lights up for. */
fun List<MaskKey>.hasShapeKeyNear(atMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Boolean =
    any { kotlin.math.abs(it.atMs - atMs) <= toleranceMs }

/**
 * The mask with its shape set at [sourceMs]: the shape itself with no keys, a
 * key there once it has any. The same rule a level or an opacity follows - once
 * a clip is keyed, a slider sets the value at the playhead, because writing the
 * static field would move the slider and not the picture.
 */
fun Mask.withShapeAt(sourceMs: Long, shape: Mask, toleranceMs: Long = KEY_TOLERANCE_MS): Mask =
    if (keys.isEmpty()) shape.copy(keys = emptyList(), track = track)
    else copy(keys = keys.upserted(MaskKey(sourceMs, shape.keyed(), keys.easingNear(sourceMs)), toleranceMs))

/** A key at [sourceMs] holding the shape the mask already has there - the keyframe button. */
fun Mask.withShapeKeyAdded(sourceMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Mask =
    copy(keys = keys.upserted(MaskKey(sourceMs, at(sourceMs).keyed(), keys.easingNear(sourceMs)), toleranceMs))

/** The shape key at [sourceMs] taken off; the last one leaves the shape standing where it was. */
fun Mask.withShapeKeyRemoved(sourceMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Mask {
    if (keys.isEmpty()) return this
    val kept = keys.filterNot { kotlin.math.abs(it.atMs - sourceMs) <= toleranceMs }
    if (kept.size == keys.size) return this
    // The last key gone leaves the shape it held, not the shape from before any
    // of the keys: taking the keys off should not move the picture.
    if (kept.isEmpty()) return at(sourceMs).copy(keys = emptyList())
    return copy(keys = kept)
}

private fun List<MaskKey>.easingNear(atMs: Long): KeyframeEasing =
    lastOrNull { it.atMs <= atMs }?.easing ?: firstOrNull()?.easing ?: KeyframeEasing.Smooth
