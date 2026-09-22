package com.squish.app.timeline

/**
 * Green-screen settings for one clip.
 *
 * Only meaningful on a clip that has something behind it, which in this app means
 * an overlay layer: keying the base track just reveals black.
 *
 * [similarity] is how far from the key color still counts as background,
 * [smoothness] is the width of the feathered edge, and [spill] is how aggressively
 * the key's color is pulled out of pixels that survived - the green fringe on hair
 * and shoulders that is not close enough to be cut but still looks wrong.
 */
data class ChromaKey(
    val keyColorArgb: Int = STANDARD_GREEN,
    /**
     * Tuned, not guessed. Neutral colors - a white shirt, a gray wall, black hair
     * - all sit about 0.33 from digital green in this chroma space, while a green
     * screen in deep shadow is still within 0.20 of it. Anything above about 0.30
     * therefore deletes the subject's shirt, and the first draft of this defaulted
     * to 0.38. 0.24 sits mid-window, with roughly 0.05 of margin on each side.
     */
    val similarity: Float = 0.24f,
    val smoothness: Float = 0.06f,
    val spill: Float = 0.1f
) {
    /**
     * The key color in the UV plane of YCbCr - chroma with luma discarded.
     *
     * This has to match the shader's rgbToUV exactly, which is why the coefficients
     * live here rather than being written out twice.
     */
    fun keyUV(): FloatArray {
        val r = ((keyColorArgb shr 16) and 0xFF) / 255f
        val g = ((keyColorArgb shr 8) and 0xFF) / 255f
        val b = (keyColorArgb and 0xFF) / 255f
        return floatArrayOf(
            r * -0.169f + g * -0.331f + b * 0.500f + 0.5f,
            r * 0.500f + g * -0.419f + b * -0.081f + 0.5f
        )
    }

    /** Guarded: both are divisors in the shader. */
    val safeSmoothness: Float get() = smoothness.coerceAtLeast(0.001f)
    val safeSpill: Float get() = spill.coerceAtLeast(0.001f)

    companion object {
        /** Digital green, the usual paint and cloth. */
        const val STANDARD_GREEN = 0xFF00B140.toInt()
        /** Digital blue, for subjects wearing green. */
        const val STANDARD_BLUE = 0xFF0047BB.toInt()

        /**
         * The usable range. Below this the screen survives in shadow; above it the
         * key starts eating neutral colors, and there is nothing useful past 0.45.
         */
        val SIMILARITY_RANGE = 0.05f..0.45f
    }
}
