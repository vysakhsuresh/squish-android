package com.squish.app.editor

import com.squish.app.timeline.Transform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * How a line of text looks, moves and sits on the picture, as decisions rather
 * than drawing. Nothing here touches Android: CaptionRenderer paints from it,
 * the strip and the export read it, and tools/jvm/TextChecks.kt executes it.
 */

/** The typeface a caption is set in. System faces only, so nothing is bundled or fetched. */
enum class TextFont(val label: String, val family: String, val bold: Boolean) {
    Sans("Sans", "sans-serif", false),
    Bold("Bold", "sans-serif-black", true),
    Serif("Serif", "serif", false),
    Condensed("Tall", "sans-serif-condensed", true),
    Mono("Mono", "monospace", false),
    Hand("Hand", "casual", false)
}

/** Which way the lines of a caption line up. */
enum class TextAlign(val label: String) { Left("Left"), Center("Centre"), Right("Right") }

/** An edge round every letter. [width] is a share of the text size; 0 is none. */
data class TextStroke(val colorArgb: Int, val width: Float) {
    val isOn: Boolean get() = width > 0f

    companion object {
        val NONE = TextStroke(BLACK, 0f)
    }
}

/**
 * A shadow under the letters. [blur] and [offset] are shares of the text size;
 * [angleDegrees] is where the shadow falls, clockwise from the right, so 90 is
 * straight down. [opacity] 0 is none.
 */
data class TextShadow(val colorArgb: Int, val opacity: Float, val blur: Float, val offset: Float, val angleDegrees: Float) {
    val isOn: Boolean get() = opacity > 0f

    companion object {
        val NONE = TextShadow(BLACK, 0f, 0.18f, 0.1f, 53f)
    }
}

/** The shape drawn behind a caption: CapCut calls these bubbles. */
enum class TextBubble(val label: String) {
    None("None"),

    /** A rounded box, the way social apps caption. */
    Box("Box"),

    /** A box with fully round ends. */
    Pill("Pill"),

    /** A band the whole width of the picture. */
    Band("Band"),

    /** A box with a tail, as if said. */
    Speech("Speech"),

    /** A dashed border and a faint fill, like a rubber stamp. */
    Stamp("Stamp")
}

/** What sits behind the letters. [radius] is a share of the text size, for the shapes that have corners. */
data class TextBackground(val colorArgb: Int, val opacity: Float, val radius: Float, val bubble: TextBubble) {
    val isOn: Boolean get() = bubble != TextBubble.None && opacity > 0f

    companion object {
        val NONE = TextBackground(BLACK, 0.66f, 0.3f, TextBubble.None)
    }
}

/**
 * Everything about a line that is a matter of style rather than of words,
 * timing or place: what Apply to all, Copy style and a saved style carry from
 * one line to another. Kept as one value so those three cannot each forget a
 * field the others remember.
 */
data class TextStyleSpec(
    val font: TextFont = TextFont.Sans,
    /** A .ttf brought in by the person, by its file name under the app's fonts; null is [font]. */
    val fontFile: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: TextAlign = TextAlign.Center,
    /** Extra space between letters, in ems. */
    val letterSpacing: Float = 0f,
    /** Line height as a multiple of the face's own. */
    val lineSpacing: Float = 1f,
    val colorArgb: Int = WHITE,
    val sizeSp: Int = DEFAULT_SIZE_SP,
    val stroke: TextStroke = TextStroke.NONE,
    val shadow: TextShadow = TextShadow.NONE,
    val background: TextBackground = TextBackground.NONE,
    /** The letter colour glowing outwards, as a neon tube. */
    val glow: Boolean = false,
    val opacity: Float = 1f
) {
    companion object {
        const val DEFAULT_SIZE_SP = 28
        const val MIN_SIZE_SP = 8
        const val MAX_SIZE_SP = 200
        const val MAX_LETTER_SPACING = 0.5f
        const val MIN_LINE_SPACING = 0.8f
        const val MAX_LINE_SPACING = 2f
        const val MAX_STROKE = 0.4f

        /**
         * What a new line of words starts as, and what the Style tab's Reset
         * puts back: outlined, since white letters with nothing round them are
         * unreadable over a bright shot. A bare [TextStyleSpec] has no edge -
         * it is what the looks and the presets build on - and Reset used to
         * apply that, so it took the outline off instead of putting it back.
         */
        val NEW_LINE: TextStyleSpec get() = TextStyleSpec(stroke = TextLook.OUTLINE_STROKE)
    }
}

/** Names for the styles a person keeps. */
object TextStyleNames {
    /**
     * The next "Style N" not already taken. Counting the list gave "Style 3"
     * after Style 2 of three was removed, and saving under a taken name
     * silently replaced the style that had it.
     */
    fun next(existing: Collection<String>): String {
        var n = 1
        while ("Style $n" in existing) n++
        return "Style $n"
    }
}

/**
 * How a line arrives, leaves and behaves in between, with each one's length:
 * what Animation's Apply to all carries from one line to the others.
 */
data class TextMotionSpec(
    val motion: TextMotion,
    val motionInMs: Long,
    val motionOut: TextExit,
    val motionOutMs: Long,
    val loop: TextLoop,
    val loopMs: Long
)

/**
 * A line's word timings kept true to the speech when the line's own ends move.
 * They are held from the line's start, so a start that moves has to move them
 * too; the strip's head handle and Cut used to leave them where they were, and
 * every word then landed late by the amount trimmed off.
 */
object TextTiming {
    /** The timings after the line's start moved [shiftMs] later: a word already said shows at once. */
    fun shifted(wordStartsMs: List<Long>, shiftMs: Long): List<Long> =
        if (shiftMs == 0L) wordStartsMs else wordStartsMs.map { (it - shiftMs).coerceAtLeast(0L) }

    /** Each half of a timed line cut in two: its words and when they start. */
    data class Halves(val firstText: String, val firstStarts: List<Long>, val secondText: String, val secondStarts: List<Long>)

    /**
     * A timed line cut [atMs] from its start: the words said before the cut
     * stay with the first half, the rest go with the second, each half's
     * timings from its own start. Null when the cut does not fall between two
     * words, or the words and the timings disagree - the halves then share the
     * words, as an untimed line's do.
     */
    fun split(text: String, wordStartsMs: List<Long>, atMs: Long): Halves? {
        val words = text.split(' ')
        if (words.size != wordStartsMs.size || words.size < 2) return null
        val before = wordStartsMs.count { it < atMs }
        if (before == 0 || before == words.size) return null
        return Halves(
            firstText = words.take(before).joinToString(" "),
            firstStarts = wordStartsMs.take(before),
            secondText = words.drop(before).joinToString(" "),
            secondStarts = shifted(wordStartsMs.drop(before), atMs)
        )
    }
}

/**
 * How a caption stands off the picture behind it: a preset for the four
 * decorations at once. A chip each, since these five are what nearly every
 * caption wants; the sliders under them are for the rest.
 */
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
    Neon("Neon");

    /** [spec] with this look's decorations and none of the others'. */
    fun applied(spec: TextStyleSpec): TextStyleSpec = spec.copy(
        stroke = if (this == Outline) OUTLINE_STROKE else TextStroke.NONE,
        shadow = if (this == Shadow) SHADOW else TextShadow.NONE,
        background = if (this == Box) BOX else TextBackground.NONE,
        glow = this == Neon
    )

    companion object {
        val OUTLINE_STROKE = TextStroke(BLACK, 0.14f)
        val SHADOW = TextShadow(BLACK, 0.78f, 0.18f, 0.1f, 53f)
        val BOX = TextBackground(BLACK, 0.66f, 0.3f, TextBubble.Box)

        /** The look [spec] is exactly, or null once its decorations have been tuned past any of them. */
        fun of(spec: TextStyleSpec): TextLook? = entries.firstOrNull { it.applied(spec) == spec }
    }
}

/** How a caption arrives. */
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
    Bounce("Bounce"),

    /** Words appear one after another - on the beat of the speech, for a caption with its words timed. */
    Words("Words"),

    /** Shrinks down from big. */
    Zoom("Zoom"),

    /** Falls in from above and bounces. */
    Drop("Drop"),

    /** Spins into place, growing. */
    Spin("Spin"),

    /** Flickers on. */
    Blink("Blink");

    /** Whether the letters are revealed rather than the whole line moved. */
    val reveals: Boolean get() = this == Typewriter || this == Words
}

/** How a caption leaves. */
enum class TextExit(val label: String) {
    None("None"),
    Fade("Fade"),

    /** Shrinks away as it fades. */
    Shrink("Shrink"),

    /** Sinks out of place. */
    Slide("Slide"),

    /** Falls off the bottom. */
    Drop("Drop"),

    /** Swells a little, then is gone. */
    Pop("Pop"),

    /** Floats up and away. */
    Rise("Float up"),

    /** Grows past full size as it fades. */
    Zoom("Zoom"),

    /** Spins away, shrinking. */
    Spin("Spin"),

    /** Flickers off. */
    Blink("Blink")
}

/** What a caption does while it is on screen. */
enum class TextLoop(val label: String) {
    None("None"),

    /** Breathes a little bigger and back. */
    Pulse("Pulse"),

    /** Rocks a few degrees either way. */
    Wobble("Wobble"),

    /** Floats up and down. */
    Bob("Bob"),

    /** A tube with a loose contact. */
    Flicker("Flicker"),

    /** A small, quick tremble. */
    Shake("Shake"),

    /** Two quick beats and a rest. */
    Heartbeat("Heartbeat"),

    /** Swings wide either way, like a sign on a chain. */
    Swing("Swing")
}

/**
 * One moment of a caption's animation.
 *
 * [rise] is how far above its resting place it sits, as a fraction of the frame's
 * height - negative is below. [reveal] is the share of its letters (or words)
 * showing. [tilt] is degrees added to its turn.
 */
data class TextFrame(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val rise: Float = 0f,
    val reveal: Float = 1f,
    val tilt: Float = 0f
)

/**
 * The arrival, the leaving and the loop, worked out together. Each of the three
 * is one of the enums above with its own length; the arrival and the leaving
 * are each cut back to half the caption so the two never overlap, whatever the
 * sliders say on a short line.
 */
object TextAnimation {
    const val DEFAULT_IN_MS = 450L
    const val DEFAULT_OUT_MS = 450L
    const val DEFAULT_LOOP_MS = 1_000L
    const val MIN_MOTION_MS = 100L
    const val MAX_MOTION_MS = 2_000L
    const val MIN_LOOP_MS = 300L
    const val MAX_LOOP_MS = 3_000L

    /** What the caption looks like [elapsedMs] into a life [totalMs] long. */
    fun frameAt(
        motion: TextMotion,
        exit: TextExit,
        loop: TextLoop,
        inMs: Long,
        outMs: Long,
        loopMs: Long,
        elapsedMs: Long,
        totalMs: Long,
        /** Where each word starts, from the caption's own start, for [TextMotion.Words]; empty spaces them evenly. */
        wordStartsMs: List<Long> = emptyList()
    ): TextFrame {
        val total = totalMs.coerceAtLeast(1L)
        val half = total / 2
        val inWindow = inMs.coerceIn(1L, half.coerceAtLeast(1L)).toFloat()
        val outWindow = outMs.coerceIn(1L, half.coerceAtLeast(1L)).toFloat()
        val remaining = total - elapsedMs
        val inT = (elapsedMs / inWindow).coerceIn(0f, 1f)
        val outT = (remaining / outWindow).coerceIn(0f, 1f)

        val arriving = when (motion) {
            TextMotion.None -> TextFrame()
            TextMotion.Fade -> TextFrame(alpha = easeOut(inT))
            TextMotion.Pop -> TextFrame(alpha = minOf(1f, inT * 2.5f), scale = overshoot(inT))
            TextMotion.Slide -> TextFrame(alpha = easeOut(inT), rise = (1f - easeOut(inT)) * 0.08f)
            TextMotion.Typewriter -> TextFrame(reveal = inT)
            TextMotion.Bounce -> TextFrame(alpha = minOf(1f, inT * 3f), rise = -bounce(inT) * 0.1f)
            TextMotion.Words -> TextFrame(reveal = wordsReveal(elapsedMs, inWindow, wordStartsMs))
            TextMotion.Zoom -> TextFrame(alpha = easeOut(inT), scale = 2.2f - 1.2f * easeOut(inT))
            TextMotion.Drop -> TextFrame(alpha = minOf(1f, inT * 3f), rise = bounce(inT) * 0.12f)
            TextMotion.Spin -> TextFrame(alpha = minOf(1f, inT * 2.5f), scale = 0.3f + 0.7f * easeOut(inT), tilt = -180f * (1f - easeOut(inT)))
            TextMotion.Blink -> TextFrame(alpha = blink(inT))
        }
        val leaving = when (exit) {
            TextExit.None -> TextFrame()
            TextExit.Fade -> TextFrame(alpha = easeOut(outT))
            TextExit.Shrink -> TextFrame(alpha = easeOut(outT), scale = 0.3f + 0.7f * easeOut(outT))
            TextExit.Slide -> TextFrame(alpha = easeOut(outT), rise = -(1f - easeOut(outT)) * 0.08f)
            TextExit.Drop -> {
                val gone = 1f - easeOut(outT)
                TextFrame(alpha = easeOut(outT), rise = -gone * gone * 0.2f)
            }
            TextExit.Pop -> (1f - outT).let { g ->
                TextFrame(alpha = if (g < 0.6f) 1f else (1f - g) / 0.4f, scale = 1f + 0.2f * sin(g * PI * 0.8).toFloat() - 0.6f * g * g)
            }
            TextExit.Rise -> TextFrame(alpha = easeOut(outT), rise = (1f - easeOut(outT)) * 0.1f)
            TextExit.Zoom -> TextFrame(alpha = easeOut(outT), scale = 1f + 0.8f * (1f - easeOut(outT)))
            TextExit.Spin -> TextFrame(alpha = easeOut(outT), scale = 0.3f + 0.7f * easeOut(outT), tilt = 180f * (1f - easeOut(outT)))
            TextExit.Blink -> TextFrame(alpha = blink(outT))
        }
        val phase = ((elapsedMs.toDouble() / loopMs.coerceAtLeast(1L)) % 1.0).toFloat()
        val wave = sin(phase * 2.0 * PI).toFloat()
        val looping = when (loop) {
            TextLoop.None -> TextFrame()
            TextLoop.Pulse -> TextFrame(scale = 1f + 0.06f * wave)
            TextLoop.Wobble -> TextFrame(tilt = 4f * wave)
            TextLoop.Bob -> TextFrame(rise = 0.012f * wave)
            // Two dips a period, never to black: a tube with a loose contact, not a strobe.
            TextLoop.Flicker -> TextFrame(alpha = 0.72f + 0.28f * abs(sin(phase * 4.0 * PI).toFloat()))
            TextLoop.Shake -> TextFrame(
                rise = 0.004f * sin(phase * 2.0 * PI * 11).toFloat(),
                tilt = 1.5f * sin(phase * 2.0 * PI * 7).toFloat()
            )
            TextLoop.Heartbeat -> TextFrame(scale = 1f + 0.08f * heartbeat(phase))
            TextLoop.Swing -> TextFrame(tilt = 9f * wave)
        }
        return TextFrame(
            alpha = arriving.alpha * leaving.alpha * looping.alpha,
            scale = arriving.scale * leaving.scale * looping.scale,
            rise = arriving.rise + leaving.rise + looping.rise,
            reveal = arriving.reveal,
            // The arrival's and leaving's turn too: Spin turns in and out.
            tilt = arriving.tilt + leaving.tilt + looping.tilt
        )
    }

    /**
     * The share of the words showing: with timings, those that have started;
     * without, spread evenly over the arrival. A word's timing is where the
     * segmenter heard it begin, so the caption keeps pace with the speech.
     */
    fun wordsReveal(elapsedMs: Long, inWindow: Float, wordStartsMs: List<Long>): Float {
        if (wordStartsMs.isEmpty()) return (elapsedMs / inWindow).coerceIn(0f, 1f)
        val shown = wordStartsMs.count { it <= elapsedMs }
        return (shown.toFloat() / wordStartsMs.size).coerceIn(0f, 1f)
    }

    /** The first [reveal] of [text]'s words, whole words only, so none is half typed. */
    fun shownWords(text: String, reveal: Float): String {
        if (reveal >= 1f) return text
        val words = text.split(' ')
        val count = ceil(words.size * reveal).toInt().coerceIn(0, words.size)
        return words.take(count).joinToString(" ")
    }

    /** The first [reveal] of [text]'s letters. */
    fun shownLetters(text: String, reveal: Float): String {
        val count = ceil(text.length * reveal).toInt().coerceIn(0, text.length)
        return text.substring(0, count)
    }

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
        return abs(kotlin.math.cos(t * PI * 2.5).toFloat()) * fall * fall
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

/** Where a line sits and how big it is: what a finger on its box changes. */
data class TextPlacement(val xFraction: Float, val yFraction: Float, val sizeSp: Int, val rotationDegrees: Float)

/**
 * A line's box on the picture, in the terms the overlay box already speaks
 * (OverlayRules: a Transform over a frame fitted to the picture), so text and
 * stickers are grabbed by the same box as a picture-in-picture. The box's
 * width and height are the letters' own, as painted for this frame, so what is
 * outlined is exactly what is drawn.
 */
object TextGeometry {
    /**
     * The Transform whose box (OverlayRules.box, aspect [glyphW]/[glyphH]) is the
     * painted letters, [glyphW] by [glyphH] pixels, centred at ([x], [y]) of a
     * [frameW] by [frameH] frame and turned [rotationDegrees].
     */
    fun transformOf(x: Float, y: Float, rotationDegrees: Float, glyphW: Float, glyphH: Float, frameW: Float, frameH: Float): Transform {
        val (fw, _) = OverlayRules.fitted(aspect(glyphW, glyphH), frameW, frameH)
        return Transform(
            scale = if (fw > 0f) glyphW / fw else 1f,
            offsetXFraction = x * 2f - 1f,
            offsetYFraction = y * 2f - 1f,
            rotationDegrees = rotationDegrees
        )
    }

    fun aspect(glyphW: Float, glyphH: Float): Float = if (glyphH > 0f) glyphW / glyphH else 1f

    /**
     * Where the line is after a gesture that took its box from [start] to [now]:
     * the size grows by the same share the box did, so a pinch that doubles the
     * box doubles the letters, measured from the gesture's start so a slow
     * pinch and a fast one end the same.
     */
    fun placed(before: TextPlacement, start: Transform, now: Transform): TextPlacement {
        val grown = if (start.scale > 0f) now.scale / start.scale else 1f
        return TextPlacement(
            xFraction = (now.offsetXFraction + 1f) / 2f,
            yFraction = (now.offsetYFraction + 1f) / 2f,
            sizeSp = (before.sizeSp * grown).roundToInt().coerceIn(TextStyleSpec.MIN_SIZE_SP, TextStyleSpec.MAX_SIZE_SP),
            rotationDegrees = OverlayRules.normalized(now.rotationDegrees)
        )
    }

    /**
     * [t] kept where a line may go: its letters between the smallest and the
     * largest size, its centre no further than a little past the frame's edge
     * (so it can never be lost), its turn in range. The overlay's own limits
     * would not do - a small sticker's box is a tenth of the frame, under the
     * overlay's floor, and would have grown the moment it was touched.
     */
    fun limited(t: Transform, start: Transform, startSizeSp: Int): Transform {
        val floor = start.scale * TextStyleSpec.MIN_SIZE_SP / startSizeSp.coerceAtLeast(1)
        val ceiling = start.scale * TextStyleSpec.MAX_SIZE_SP / startSizeSp.coerceAtLeast(1)
        return Transform(
            scale = t.scale.coerceIn(floor, maxOf(floor, ceiling)),
            offsetXFraction = t.offsetXFraction.coerceIn(-OFFSET_MAX, OFFSET_MAX),
            offsetYFraction = t.offsetYFraction.coerceIn(-OFFSET_MAX, OFFSET_MAX),
            rotationDegrees = OverlayRules.normalized(t.rotationDegrees)
        )
    }

    /** What the picture says while a finger is on a line: where it is, or its size and turn. */
    fun readout(t: Transform, start: Transform, startSizeSp: Int, moving: Boolean): String =
        if (moving) OverlayRules.readout(t, moving = true)
        else {
            val size = placed(TextPlacement(0.5f, 0.5f, startSizeSp, 0f), start, t).sizeSp
            "Size $size · ${OverlayRules.normalized(t.rotationDegrees).roundToInt()}°"
        }

    /** A little past the edge, in half-frames: the centre stays in reach. */
    const val OFFSET_MAX = 1.1f
}

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
    val yFraction: Float,
    val exit: TextExit = TextExit.Fade
) {
    Headline("Headline", "BIG NEWS", TextFont.Bold, TextLook.Outline, TextMotion.Pop, WHITE, 44, 0.45f),
    Subtitle("Subtitle", "Say it here", TextFont.Sans, TextLook.Box, TextMotion.Fade, WHITE, 26, 0.84f),
    LowerThird("Name tag", "Your name", TextFont.Condensed, TextLook.Box, TextMotion.Slide, 0xFFFFD166.toInt(), 28, 0.76f),
    Neon("Neon", "Tonight", TextFont.Hand, TextLook.Neon, TextMotion.Fade, 0xFFFF4FD8.toInt(), 40, 0.4f),
    Typewriter("Typewriter", "Once upon a time…", TextFont.Mono, TextLook.Shadow, TextMotion.Typewriter, WHITE, 26, 0.5f),
    Bounce("Bounce", "Wow!", TextFont.Bold, TextLook.Outline, TextMotion.Bounce, 0xFF5CE1E6.toInt(), 48, 0.35f);

    /** This title's style, on its own. */
    val style: TextStyleSpec
        get() = look.applied(TextStyleSpec(font = font, colorArgb = colorArgb, sizeSp = sizeSp))
}

/**
 * A caption style in one tap - for a line of subtitles, where a title's words
 * and place would be wrong but its face is what is wanted. Apply to all
 * carries the choice across every line.
 */
enum class CaptionStylePreset(val label: String, val style: TextStyleSpec) {
    Classic("Classic", TextLook.Outline.applied(TextStyleSpec())),
    Boxed("Boxed", TextLook.Box.applied(TextStyleSpec())),
    Bold("Bold", TextLook.Outline.applied(TextStyleSpec(font = TextFont.Bold, sizeSp = 32, stroke = TextStroke(BLACK, 0.2f)))),
    Yellow("Yellow", TextLook.Outline.applied(TextStyleSpec(font = TextFont.Bold, colorArgb = 0xFFFFD166.toInt(), sizeSp = 30))),
    Soft("Soft", TextLook.Shadow.applied(TextStyleSpec(font = TextFont.Serif, sizeSp = 30))),
    Pill("Pill", TextStyleSpec(background = TextBackground(WHITE, 0.9f, 1f, TextBubble.Pill), colorArgb = BLACK)),
    Neon("Neon", TextLook.Neon.applied(TextStyleSpec(font = TextFont.Hand, colorArgb = 0xFF5CE1E6.toInt(), sizeSp = 32))),
    Mono("Mono", TextLook.Box.applied(TextStyleSpec(font = TextFont.Mono, letterSpacing = 0.08f)))
}

/**
 * The voice effects live with the clip now (timeline/VoiceEffect.kt); the
 * name here keeps every panel that spoke of them working unchanged.
 */
typealias VoiceEffect = com.squish.app.timeline.VoiceEffect

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
