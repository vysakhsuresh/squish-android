package com.squish.app.editor

/**
 * A crop chosen by hand, as fractions of the frame.
 *
 * The fixed ratios answer "what am I posting to"; this answers "what is the shot".
 * They are different questions and only the first had a control. Cropping to 1:1
 * takes the middle square whether or not the subject is in the middle, so anyone
 * whose subject was off to one side had no way to say so and had to accept the
 * centre or not crop at all.
 *
 * Fractions rather than pixels so the rectangle survives everything that changes
 * the frame's size: a proxy, a quality preset, a rotation. Zero is the left and
 * top edge, one the right and bottom, and the rectangle is always inside that -
 * a crop that reached outside the picture would ask the renderer for footage
 * nobody shot.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when the rectangle is the whole frame, which is the same as no crop. */
    val isFull: Boolean
        get() = left <= EPSILON && top <= EPSILON && right >= 1f - EPSILON && bottom >= 1f - EPSILON

    /** The shape of what is kept, given the shape of what it was cut from. */
    fun aspect(sourceAspect: Float): Float {
        if (height <= 0f || width <= 0f) return sourceAspect
        return sourceAspect * width / height
    }

    /**
     * The same rectangle in normalized device coordinates, which is what Media3's
     * `Crop` takes: -1 to 1, and with the vertical axis pointing up rather than
     * down. Getting that flip wrong crops the top when you asked for the bottom,
     * which looks like a working feature until someone checks.
     */
    fun toNdc(): FloatArray = floatArrayOf(
        left * 2f - 1f,          // left
        right * 2f - 1f,         // right
        1f - bottom * 2f,        // bottom, flipped
        1f - top * 2f            // top, flipped
    )

    companion object {
        const val EPSILON = 0.001f

        /** Nothing smaller than this, in either direction. */
        const val MIN_SIDE = 0.08f

        /**
         * A rectangle clamped to something a renderer will accept.
         *
         * Every edge inside the frame, no edge past its opposite, and never so
         * thin that the output would round to zero pixels. Handles get dragged
         * past each other and off the edge constantly, and every one of those
         * gestures has to end somewhere sane rather than at an exception during
         * export twenty minutes later.
         */
        fun of(left: Float, top: Float, right: Float, bottom: Float): CropRect {
            // A NaN survives coerceIn untouched and then poisons every comparison
            // downstream, so it is answered before the clamping rather than after.
            // Gesture maths divides by a measured width, and a width is zero for
            // one frame on every layout.
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                return CropRect()
            }
            val l = left.coerceIn(0f, 1f - MIN_SIDE)
            val t = top.coerceIn(0f, 1f - MIN_SIDE)
            val r = right.coerceIn(l + MIN_SIDE, 1f)
            val b = bottom.coerceIn(t + MIN_SIDE, 1f)
            return CropRect(l, t, r, b)
        }

        /**
         * The largest rectangle of [ratio] that fits, centred.
         *
         * What a fixed-ratio choice means as a rectangle, so that switching from
         * 1:1 to custom starts from the square you were already looking at rather
         * than from the whole frame.
         */
        fun centred(ratio: Float, sourceAspect: Float): CropRect {
            if (ratio <= 0f || sourceAspect <= 0f) return CropRect()
            // width/height of the crop in fractions, given that the frame itself
            // is sourceAspect wide for every unit of height.
            val wanted = ratio / sourceAspect
            return if (wanted <= 1f) {
                val half = wanted / 2f
                of(0.5f - half, 0f, 0.5f + half, 1f)
            } else {
                val half = (1f / wanted) / 2f
                of(0f, 0.5f - half, 1f, 0.5f + half)
            }
        }
    }
}
