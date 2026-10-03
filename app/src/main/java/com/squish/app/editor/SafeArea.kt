package com.squish.app.editor

/** How much of each edge of the frame is covered, as a fraction of it. */
data class SafeInsets(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    val height: Float get() = (1f - top - bottom).coerceAtLeast(0f)
    val width: Float get() = (1f - left - right).coerceAtLeast(0f)

    /** Whether a point in the frame, measured 0..1 from the top left, is covered. */
    fun covers(x: Float, y: Float): Boolean =
        y < top || y > 1f - bottom || x < left || x > 1f - right
}

/**
 * Where a platform's own buttons and captions sit over your video.
 *
 * A reel is not watched in a rectangle - it is watched under a column of
 * buttons on the right and a block of caption along the bottom, and anything
 * put there is covered. Every editor in this bracket leaves you to find that
 * out after posting.
 *
 * The numbers are fractions of the frame, taken off each app's own layout on a
 * tall phone. They are **approximate and they move**: the apps change their
 * furniture without telling anyone, and a guide claiming to be exact would be
 * wrong within a release. They are drawn as a hint and never written into the
 * file, and the sheet says so.
 */
enum class SafeArea(val label: String, val insets: SafeInsets) {
    /** Every one of them at once: the rectangle that is clear wherever it goes. */
    Everywhere("Anywhere", SafeInsets(0.08f, 0.25f, 0.03f, 0.17f)),

    /** The caption block and the button column; the tallest furniture of the three. */
    TikTok("TikTok", SafeInsets(0.07f, 0.25f, 0.03f, 0.17f)),

    /** Reels: a shorter caption, the same column of buttons. */
    Reels("Reels", SafeInsets(0.08f, 0.20f, 0.03f, 0.15f)),

    /** Shorts: the title sits lower and the buttons are narrower. */
    Shorts("Shorts", SafeInsets(0.06f, 0.17f, 0.03f, 0.15f));

    companion object {
        /**
         * [Everywhere] is meant to be the worst of each edge across the real
         * platforms, so it is worked out rather than typed - a number added to
         * one of them below would otherwise quietly stop being covered.
         */
        fun strictest(): SafeInsets {
            val real = entries.filter { it != Everywhere }.map { it.insets }
            return SafeInsets(
                top = real.maxOf { it.top },
                bottom = real.maxOf { it.bottom },
                left = real.maxOf { it.left },
                right = real.maxOf { it.right }
            )
        }
    }
}
