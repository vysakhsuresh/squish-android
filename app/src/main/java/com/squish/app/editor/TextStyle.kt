package com.squish.app.editor

import android.graphics.Typeface

/** The typeface a caption is set in. System faces only, so nothing is bundled or fetched. */
enum class TextFont(val label: String, val family: String, val bold: Boolean) {
    Sans("Sans", "sans-serif", false),
    Bold("Bold", "sans-serif-black", true),
    Serif("Serif", "serif", false),
    Condensed("Tall", "sans-serif-condensed", true),
    Mono("Mono", "monospace", false),
    Hand("Hand", "casual", false);

    fun typeface(): Typeface =
        Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/** How a caption stands off the picture behind it. */
enum class TextLook(val label: String) {
    /** Just the letters. Readable only on a calm background. */
    Plain("Plain"),

    /** A dark edge round every letter - legible on anything, the subtitle default. */
    Outline("Outline"),

    /** A rounded dark box behind the line, the way social apps caption. */
    Box("Box"),

    /** A soft drop shadow. */
    Shadow("Shadow"),

    /** The letter colour glowing outwards. */
    Neon("Neon")
}

/** How a caption arrives and leaves. */
enum class TextMotion(val label: String) {
    None("None"),
    Fade("Fade"),

    /** Grows in past full size and settles. */
    Pop("Pop"),

    /** Rises into place from below. */
    Slide("Slide"),

    /** Letters appear one after another. */
    Typewriter("Type"),

    /** Drops in and bounces to rest. */
    Bounce("Bounce");

    /**
     * What the caption looks like [elapsedMs] into its life and [remainingMs]
     * before its end. Arrival and departure are each [MOTION_MS] long, or less on
     * a very short caption so the two never overlap.
     */
    fun frameAt(elapsedMs: Long, remainingMs: Long, totalMs: Long): TextFrame {
        val window = minOf(MOTION_MS, totalMs / 3).coerceAtLeast(1L).toFloat()
        val inT = (elapsedMs / window).coerceIn(0f, 1f)
        val outT = (remainingMs / window).coerceIn(0f, 1f)
        // Every motion leaves by fading, so no caption blinks out mid-word.
        val leaving = if (this == None) 1f else easeOut(outT)
        return when (this) {
            None -> TextFrame()
            Fade -> TextFrame(alpha = easeOut(inT) * leaving)
            Pop -> TextFrame(alpha = minOf(1f, inT * 2.5f) * leaving, scale = overshoot(inT))
            Slide -> TextFrame(alpha = easeOut(inT) * leaving, rise = (1f - easeOut(inT)) * 0.08f)
            Typewriter -> TextFrame(alpha = leaving, reveal = typewriterReveal(elapsedMs, totalMs))
            Bounce -> TextFrame(alpha = minOf(1f, inT * 3f) * leaving, rise = -bounce(inT) * 0.1f)
        }
    }

    companion object {
        const val MOTION_MS = 450L

        private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

        /** 0 to 1 with a single overshoot past 1, for a pop. */
        private fun overshoot(t: Float): Float {
            val s = 1.70158f * 1.3f
            val u = t - 1f
            return (1f + (s + 1f) * u * u * u + s * u * u).coerceAtLeast(0.01f)
        }

        /** Falls from above and settles with two shrinking bounces. 1 at the start, 0 at rest. */
        private fun bounce(t: Float): Float {
            if (t >= 1f) return 0f
            val fall = 1f - t
            return kotlin.math.abs(kotlin.math.cos(t * Math.PI * 2.5).toFloat()) * fall * fall
        }

        /** Letters arrive over the first 40% of the caption, never slower than 12 a second. */
        private fun typewriterReveal(elapsedMs: Long, totalMs: Long): Float {
            val span = (totalMs * 0.4f).coerceIn(300f, 2_000f)
            return (elapsedMs / span).coerceIn(0f, 1f)
        }
    }
}

/**
 * One moment of a caption's animation.
 *
 * [rise] is how far above its resting place it sits, as a fraction of the frame's
 * height - negative is below. [reveal] is the share of its letters showing.
 */
data class TextFrame(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val rise: Float = 0f,
    val reveal: Float = 1f
)

/**
 * A one-tap title: the text, how it looks, where it sits and how it moves, chosen
 * together so the result looks designed rather than assembled.
 */
enum class TitlePreset(
    val label: String,
    val sample: String,
    val font: TextFont,
    val look: TextLook,
    val motion: TextMotion,
    val colorArgb: Int,
    val sizeSp: Int,
    val yFraction: Float
) {
    Headline("Headline", "BIG NEWS", TextFont.Bold, TextLook.Outline, TextMotion.Pop, 0xFFFFFFFF.toInt(), 44, 0.45f),
    Subtitle("Subtitle", "Say it here", TextFont.Sans, TextLook.Box, TextMotion.Fade, 0xFFFFFFFF.toInt(), 26, 0.84f),
    LowerThird("Name tag", "Your name", TextFont.Condensed, TextLook.Box, TextMotion.Slide, 0xFFFFD166.toInt(), 28, 0.76f),
    Neon("Neon", "Tonight", TextFont.Hand, TextLook.Neon, TextMotion.Fade, 0xFFFF4FD8.toInt(), 40, 0.4f),
    Typewriter("Typewriter", "Once upon a time…", TextFont.Mono, TextLook.Shadow, TextMotion.Typewriter, 0xFFFFFFFF.toInt(), 26, 0.5f),
    Bounce("Bounce", "Wow!", TextFont.Bold, TextLook.Outline, TextMotion.Bounce, 0xFF5CE1E6.toInt(), 48, 0.35f)
}

/**
 * A voice effect on the clip's own sound. Pitch effects shift the voice without
 * changing its timing; the rest are processed by VoiceProcessor.
 */
enum class VoiceEffect(val label: String, val pitch: Float = 1f) {
    None("None"),
    Chipmunk("Chipmunk", pitch = 1.6f),
    Deep("Deep", pitch = 0.72f),
    Robot("Robot"),
    Echo("Echo"),
    Radio("Radio")
}
