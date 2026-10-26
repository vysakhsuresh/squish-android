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
    Hand("Hand", "casual", false),
    // More of the faces every Android phone carries - by family name, so the
    // preview and the file (CaptionRenderer) find the same one.
    Light("Light", "sans-serif-light", false),
    Thin("Thin", "sans-serif-thin", false),
    Medium("Medium", "sans-serif-medium", false),
    Script("Script", "cursive", false),
    SmallCaps("Caps", "sans-serif-smallcaps", false),
    SerifMono("Typed", "serif-monospace", false)
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
 * The letters' fill (EDGE_PLAN E3): one colour - the line's own - or a
 * gradient of two or three [colors] at [angleDegrees], 0 running left to
 * right and 90 top to bottom.
 */
data class TextFill(val colors: List<Int> = emptyList(), val angleDegrees: Float = 90f) {
    val isGradient: Boolean get() = colors.size >= 2

    companion object {
        val SOLID = TextFill()
        const val MAX_STOPS = 3
    }
}

/**
 * The geometry text with depth is drawn with (EDGE_PLAN E3), with no Android
 * in it: tools/jvm/TextDepthChecks.kt executes it, and CaptionRenderer draws
 * with what it answers.
 */
object TextDepth {

    /**
     * Where a gradient at [angleDegrees] starts and ends across a box [w] by [h]
     * (x0, y0, x1, y1): through the box's middle, long enough that its first
     * and last colours reach the corners - a gradient whose ends sat inside
     * the box painted its corners flat.
     */
    fun gradientEnds(w: Float, h: Float, angleDegrees: Float): FloatArray {
        val a = Math.toRadians(angleDegrees.toDouble())
        val dx = kotlin.math.cos(a).toFloat()
        val dy = kotlin.math.sin(a).toFloat()
        val half = (w * kotlin.math.abs(dx) + h * kotlin.math.abs(dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
    }

    /** The angle a line bent by [bend] spans: a full bend, either way, is half a circle. */
    private fun sweep(bend: Float): Double = kotlin.math.abs(bend.coerceIn(-1f, 1f)) * PI

    /** The radius of the circle a line [width] wide is laid on when bent by [bend]; infinite for a straight line. */
    fun arcRadius(width: Float, bend: Float): Float {
        val s = sweep(bend)
        return if (s <= 1e-6) Float.POSITIVE_INFINITY else (width / s).toFloat()
    }

    /** How far the middle of the bent line stands off the line through its ends. */
    fun arcRise(width: Float, bend: Float): Float {
        val s = sweep(bend)
        if (s <= 1e-6) return 0f
        return (arcRadius(width, bend) * (1 - kotlin.math.cos(s / 2))).toFloat()
    }

    /** How far apart the bent line's ends are: narrower than the line laid flat. */
    fun arcChord(width: Float, bend: Float): Float {
        val s = sweep(bend)
        if (s <= 1e-6) return width
        return (2 * arcRadius(width, bend) * kotlin.math.sin(s / 2)).toFloat()
    }

    /** The half-angle, in degrees, a bent line spans either side of its middle. */
    fun arcHalfDegrees(bend: Float): Float = Math.toDegrees(sweep(bend) / 2).toFloat()
}

/** How the word being said stands out from the rest of its line (EDGE_PLAN E2). */
enum class WordMode(val label: String) {
    None("Off"),

    /** The word being said in the highlight colour. */
    Highlight("Colour"),

    /** A box in the highlight colour behind the word being said. */
    Box("Box"),

    /** Every word said so far in the highlight colour, as a karaoke line fills. */
    Karaoke("Karaoke"),

    /** The word being said bigger, and in the highlight colour. */
    Pop("Pop"),

    /** Only the word being said, one at a time. */
    One("One word")
}

/**
 * A line's words styled by when each is said. Driven by the line's own word
 * timings when it has them (auto-captions do), else spread evenly over the
 * line - so a line typed by hand can be a karaoke line too.
 *
 * [perScreen] shows the words a few at a time, the page with the word being
 * said: the short punchy captions of a reel. 0 is the whole line at once.
 */
data class WordStyle(
    val mode: WordMode = WordMode.None,
    val colorArgb: Int = HIGHLIGHT_YELLOW,
    val perScreen: Int = 0
) {
    val isOn: Boolean get() = mode != WordMode.None || perScreen > 0

    companion object {
        val NONE = WordStyle()
        const val HIGHLIGHT_YELLOW = 0xFFFFD60A.toInt()
        const val MAX_PER_SCREEN = 8

        /** How much bigger a popped word is than its neighbours. */
        const val POP_SCALE = 1.22f
    }
}

/**
 * Which words of a line are on screen at a moment and which one is being said.
 * [from] until [to] are indices into the line's words; [active] is the word
 * being said, or -1 before the first has begun.
 */
data class WordState(val from: Int, val to: Int, val active: Int)

/**
 * The arithmetic of [WordStyle], with no Android in it, so
 * tools/jvm/CaptionWordsChecks.kt executes it. The preview's caption layer and
 * the export's overlay both get a line's [WordState] through
 * [TextOverlayItem.frameAt] and paint it with the one CaptionRenderer.
 */
object CaptionWords {

    /** A line's words, split as its timings were counted - on single spaces. */
    fun words(text: String): List<String> = text.split(' ')

    /**
     * When each of [count] words starts, from the line's start: [wordStartsMs]
     * when it has one per word, else even shares of [totalMs]. A timing list
     * that has fallen out of step with the words - retyped, translated - would
     * light the wrong ones, so it is not used at all.
     */
    fun starts(count: Int, wordStartsMs: List<Long>, totalMs: Long): List<Long> {
        if (count <= 0) return emptyList()
        if (wordStartsMs.size == count) return wordStartsMs
        val total = totalMs.coerceAtLeast(1L)
        return List(count) { i -> total * i / count }
    }

    /** The word being said [elapsedMs] into the line: the last to have started, or -1 before the first. */
    fun activeAt(starts: List<Long>, elapsedMs: Long): Int = starts.indexOfLast { it <= elapsedMs }

    /** The line's words at [elapsedMs] under [style]; null when the style is off. */
    fun state(text: String, wordStartsMs: List<Long>, elapsedMs: Long, totalMs: Long, style: WordStyle): WordState? {
        if (!style.isOn) return null
        val count = words(text).size
        if (count == 0) return null
        val active = activeAt(starts(count, wordStartsMs, totalMs), elapsedMs)
        val per = if (style.mode == WordMode.One) 1 else style.perScreen.coerceIn(0, WordStyle.MAX_PER_SCREEN)
        if (per <= 0 || per >= count) return WordState(0, count, active)
        // Before the first word the first page stands, so the line is never blank.
        val from = active.coerceAtLeast(0) / per * per
        return WordState(from, minOf(count, from + per), active)
    }

    /**
     * The words on screen: the page, and of that only those a Words arrival has
     * revealed, [revealed] counting from the line's first word.
     */
    fun shownWords(text: String, state: WordState, revealed: Int = Int.MAX_VALUE): List<String> {
        val all = words(text)
        val to = minOf(state.to, all.size, revealed.coerceAtLeast(state.from))
        return if (state.from >= to) emptyList() else all.subList(state.from, to)
    }

    /** The whole page, for the box a page is laid out in - so revealing a word does not move the others. */
    fun pageText(text: String, state: WordState): String =
        words(text).let { all -> all.subList(state.from.coerceIn(0, all.size), state.to.coerceIn(state.from.coerceIn(0, all.size), all.size)) }.joinToString(" ")

    /** Where word [index] of a line (an index into the whole line) sits in [shown]'s joined text, or null when it is not shown. */
    fun charRange(shown: List<String>, state: WordState, index: Int): IntRange? {
        val at = index - state.from
        if (at !in shown.indices) return null
        var start = 0
        for (i in 0 until at) start += shown[i].length + 1
        return start until start + shown[at].length
    }

    /**
     * [shown] with each word's picked emoji after it (EDGE_PLAN E2), [from]
     * being the place in the whole line of its first word. Held to its word by
     * a no-break space, so the text still splits into the same words and every
     * range worked out on it - the word being said, a colour, a picked-out
     * word - still lands on the right one.
     */
    fun decorated(shown: String, from: Int, emoji: Map<Int, String>): String {
        if (emoji.isEmpty() || shown.isEmpty()) return shown
        return words(shown).mapIndexed { i, w ->
            val e = emoji[from + i]
            if (e.isNullOrBlank() || w.isEmpty()) w else "$w${NO_BREAK_SPACE}$e"
        }.joinToString(" ")
    }

    /** Where each picked-out word of the line (by its place in the whole line) sits in [shown], from [from]. */
    fun emphasised(shown: String, from: Int, emphasis: Set<Int>): List<IntRange> {
        if (emphasis.isEmpty() || shown.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var at = 0
        words(shown).forEachIndexed { i, w ->
            if ((from + i) in emphasis && w.isNotEmpty()) out += at until at + w.length
            at += w.length + 1
        }
        return out
    }

    /** U+00A0, spelled by its number: an invisible character in the source is one nobody can see to check. */
    val NO_BREAK_SPACE: Char = Char(0xA0)

    /** How much bigger a picked-out word is than the rest of its line. */
    const val EMPHASIS_SCALE = 1.3f

    /** How big the second language is beside the first. */
    const val SECOND_LINE_SCALE = 0.72f

    /**
     * The block drawn for a line kept in two languages (EDGE_PLAN P4): the line,
     * and under it the other language, when both are shown. Returns the text
     * and where the second language starts in it, or -1 for none - appended
     * after the first, so every range worked out on the first is unchanged.
     */
    fun withSecondLine(first: String, second: String?, showBoth: Boolean): Pair<String, Int> {
        if (!showBoth || second.isNullOrBlank() || first.isEmpty()) return first to -1
        return "$first\n${second.trim()}" to first.length + 1
    }

    /**
     * Where a line kept in two languages is drawn (EDGE_PLAN P4): its centre
     * [y] (a fraction of the frame's height) moved only as far as keeps the
     * block - [halfHeight] each side of it, a fraction too - [margin] inside
     * the frame. The second language makes a line taller than it was placed
     * for, and one placed at the default 85% would otherwise hang its
     * translation off the bottom of the picture. A block taller than the frame
     * is centred.
     */
    fun keptInFrame(y: Float, halfHeight: Float, margin: Float = 0.02f): Float {
        val lo = margin + halfHeight
        val hi = 1f - margin - halfHeight
        return if (lo > hi) 0.5f else y.coerceIn(lo, hi)
    }

    /** The stretch of [shown]'s text the highlight colour covers under [mode], or null for none. */
    fun coloured(mode: WordMode, shown: List<String>, state: WordState): IntRange? {
        if (state.active < 0) return null
        return when (mode) {
            WordMode.Highlight, WordMode.Pop, WordMode.Box -> charRange(shown, state, state.active)
            WordMode.Karaoke -> {
                // Everything said so far on this page: up to the end of the word being said.
                val last = minOf(state.active, state.from + shown.size - 1)
                if (last < state.from) null else charRange(shown, state, last)?.let { 0..it.last }
            }
            WordMode.None, WordMode.One -> null
        }
    }

    /**
     * Ink that reads on [argb]: black on a light highlight box, white on a dark
     * one. A yellow box under white letters made the word being said the one
     * that could not be read.
     */
    fun inkOn(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (luma > 150f) BLACK else WHITE
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
    val opacity: Float = 1f,
    /** The word being said standing out, and how many words at a time. */
    val words: WordStyle = WordStyle.NONE,
    /** A gradient in place of the one colour (EDGE_PLAN E3). */
    val fill: TextFill = TextFill.SOLID,
    /** The glow's own colour - null is the letters' - and how far it reaches, 0.2 to 2. */
    val glowColorArgb: Int? = null,
    val glowStrength: Float = 1f,
    /** A second edge, outside the first. */
    val outline2: TextStroke = TextStroke.NONE,
    /** Room round the letters inside a bubble, as a multiple of the usual. */
    val padding: Float = 1f,
    /** The line bent into an arc, -1 (a smile) to 1 (a rainbow); 0 is straight. */
    val arc: Float = 0f
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
    val tilt: Float = 0f,
    /** Which words are on screen and which is being said, for a line with a [WordStyle]; null shows the line whole. */
    val words: WordState? = null
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

    /**
     * The first [reveal] of [text]'s words, whole words only, so none is half
     * typed.
     *
     * [COUNT_EPSILON] is subtracted before the ceiling because a Words arrival
     * hands this a *count* laundered through a fraction: [wordsReveal] returns
     * `shown / size` and this multiplies by the same size. The round trip
     * through a Float overshoots - 7 of 13 comes back as 7.0000005, whose
     * ceiling is 8 - so the eighth word appeared on the seventh's beat and sat
     * there until its own. A whisker's worth of a word is below anything a
     * reveal means and above the error.
     */
    fun shownWords(text: String, reveal: Float): String {
        if (reveal >= 1f) return text
        val words = text.split(' ')
        val count = ceil(words.size * reveal - COUNT_EPSILON).toInt().coerceIn(0, words.size)
        return words.take(count).joinToString(" ")
    }

    /** The first [reveal] of [text]'s letters. */
    fun shownLetters(text: String, reveal: Float): String {
        val count = ceil(text.length * reveal - COUNT_EPSILON).toInt().coerceIn(0, text.length)
        return text.substring(0, count)
    }

    /**
     * How far above a whole number a count may land and still be that number.
     *
     * Small enough that no reveal anyone can set is moved by it - a thousandth
     * of a word is under a millisecond of a line's arrival - and large enough
     * to swallow the few ULPs a Float round trip through `k / n` and back
     * leaves behind.
     */
    private const val COUNT_EPSILON = 1e-3f

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
    val exit: TextExit = TextExit.Fade,
    /** What a template adds on top of its look: a gradient, a bend, a second edge (EDGE_PLAN E3). */
    private val extra: (TextStyleSpec) -> TextStyleSpec = { it }
) {
    Headline("Headline", "BIG NEWS", TextFont.Bold, TextLook.Outline, TextMotion.Pop, WHITE, 44, 0.45f),
    Subtitle("Subtitle", "Say it here", TextFont.Sans, TextLook.Box, TextMotion.Fade, WHITE, 26, 0.84f),
    LowerThird("Name tag", "Your name", TextFont.Condensed, TextLook.Box, TextMotion.Slide, 0xFFFFD166.toInt(), 28, 0.76f),
    Neon("Neon", "Tonight", TextFont.Hand, TextLook.Neon, TextMotion.Fade, 0xFFFF4FD8.toInt(), 40, 0.4f),
    Typewriter("Typewriter", "Once upon a time…", TextFont.Mono, TextLook.Shadow, TextMotion.Typewriter, WHITE, 26, 0.5f),
    Bounce("Bounce", "Wow!", TextFont.Bold, TextLook.Outline, TextMotion.Bounce, 0xFF5CE1E6.toInt(), 48, 0.35f),
    Breaking("Breaking", "BREAKING", TextFont.Bold, TextLook.Box, TextMotion.Slide, WHITE, 34, 0.2f),
    Quote("Quote", "“Say less.”", TextFont.Serif, TextLook.Shadow, TextMotion.Fade, WHITE, 34, 0.5f),
    Sale("Sale", "50% OFF", TextFont.Bold, TextLook.Outline, TextMotion.Pop, 0xFFFFD166.toInt(), 52, 0.4f, TextExit.Pop),
    BigNumber("Big number", "3", TextFont.Bold, TextLook.Outline, TextMotion.Zoom, WHITE, 80, 0.45f, TextExit.Zoom),
    Chapter("Chapter", "Chapter one", TextFont.Serif, TextLook.Plain, TextMotion.Fade, WHITE, 32, 0.5f),
    Vlog("Vlog", "Day 1 in Goa", TextFont.Hand, TextLook.Shadow, TextMotion.Drop, WHITE, 38, 0.3f, TextExit.Rise),
    Gaming("Gaming", "GG!", TextFont.Condensed, TextLook.Neon, TextMotion.Blink, 0xFF7CFF6B.toInt(), 52, 0.4f, TextExit.Blink),
    Love("Love", "Forever", TextFont.Hand, TextLook.Neon, TextMotion.Pop, 0xFFFF6B8B.toInt(), 46, 0.45f),
    Follow("Follow", "Follow for more", TextFont.Bold, TextLook.Box, TextMotion.Slide, 0xFFFFD166.toInt(), 28, 0.8f, TextExit.Slide),
    Cinema("Cinema", "A FILM BY YOU", TextFont.Condensed, TextLook.Plain, TextMotion.Fade, WHITE, 26, 0.5f),
    Spin("Spin", "Let's go!", TextFont.Bold, TextLook.Outline, TextMotion.Spin, 0xFF5CE1E6.toInt(), 46, 0.4f, TextExit.Spin),

    // Text with depth (EDGE_PLAN E3).
    Sunset("Sunset", "Golden hour", TextFont.Bold, TextLook.Shadow, TextMotion.Fade, WHITE, 48, 0.4f,
        extra = { it.copy(fill = TextFill(listOf(0xFFFFD166.toInt(), 0xFFFF6B6B.toInt(), 0xFFC44DFF.toInt()), 90f)) }),
    Ocean("Ocean", "Deep blue", TextFont.Serif, TextLook.Plain, TextMotion.Slide, WHITE, 46, 0.45f,
        extra = { it.copy(fill = TextFill(listOf(0xFF5CE1E6.toInt(), 0xFF2F80ED.toInt()), 0f), outline2 = TextStroke(WHITE, 0.08f)) }),
    Rainbow("Rainbow", "Summer days", TextFont.Bold, TextLook.Outline, TextMotion.Pop, WHITE, 44, 0.35f,
        extra = { it.copy(arc = 0.45f, fill = TextFill(listOf(0xFFFF4FA3.toInt(), 0xFFFFD166.toInt(), 0xFF5CE1E6.toInt()), 0f)) }),
    Smile("Smile", "Have a nice day", TextFont.Hand, TextLook.Shadow, TextMotion.Bounce, 0xFFFFD166.toInt(), 40, 0.6f,
        extra = { it.copy(arc = -0.4f) }),
    Badge("Badge", "OFFICIAL", TextFont.Condensed, TextLook.Outline, TextMotion.Zoom, WHITE, 42, 0.4f, TextExit.Zoom,
        extra = { it.copy(arc = 0.6f, outline2 = TextStroke(0xFFFFD166.toInt(), 0.1f)) }),
    PopArt("Pop art", "So good", TextFont.Bold, TextLook.Outline, TextMotion.Pop, 0xFFFF4FA3.toInt(), 52, 0.45f, TextExit.Pop,
        extra = { it.copy(stroke = TextStroke(WHITE, 0.16f), outline2 = TextStroke(BLACK, 0.12f)) }),
    Comic("Comic", "POW!", TextFont.Bold, TextLook.Outline, TextMotion.Bounce, 0xFFFFD166.toInt(), 64, 0.4f, TextExit.Pop,
        extra = { it.copy(stroke = TextStroke(BLACK, 0.2f), outline2 = TextStroke(0xFFFF4FA3.toInt(), 0.14f)) }),
    Chrome("Chrome", "PREMIUM", TextFont.Condensed, TextLook.Shadow, TextMotion.Fade, WHITE, 46, 0.45f,
        extra = { it.copy(fill = TextFill(listOf(WHITE, 0xFF9AA3AD.toInt(), WHITE), 90f), letterSpacing = 0.12f) }),
    Fire("Fire", "HOT DEAL", TextFont.Bold, TextLook.Neon, TextMotion.Blink, 0xFFFFD166.toInt(), 52, 0.4f, TextExit.Blink,
        extra = { it.copy(fill = TextFill(listOf(0xFFFFE066.toInt(), 0xFFFF6B1A.toInt()), 90f), glowColorArgb = 0xFFFF3B30.toInt(), glowStrength = 1.6f) }),
    Ice("Ice", "Chill", TextFont.Hand, TextLook.Neon, TextMotion.Fade, 0xFFBFEFFF.toInt(), 50, 0.45f,
        extra = { it.copy(glowColorArgb = 0xFF2F80ED.toInt(), glowStrength = 1.4f) }),
    Party("Party", "Let's celebrate", TextFont.Bold, TextLook.Outline, TextMotion.Spin, WHITE, 44, 0.4f, TextExit.Spin,
        extra = { it.copy(arc = 0.3f, fill = TextFill(listOf(0xFF7CFF6B.toInt(), 0xFF5CE1E6.toInt(), 0xFFFF4FA3.toInt()), 45f)) }),
    Tag("Tag", "NEW", TextFont.Bold, TextLook.Box, TextMotion.Slide, WHITE, 40, 0.25f, TextExit.Slide,
        extra = { it.copy(background = TextBackground(0xFFFF3B30.toInt(), 0.95f, 0.3f, TextBubble.Box), padding = 1.8f) }),
    Banner("Banner", "Grand opening", TextFont.Serif, TextLook.Box, TextMotion.Slide, WHITE, 34, 0.5f, TextExit.Slide,
        extra = { it.copy(background = TextBackground(0xFF1B1B3A.toInt(), 0.9f, 0.3f, TextBubble.Band), padding = 1.6f, outline2 = TextStroke.NONE) }),
    Retro("Retro", "Since 1985", TextFont.Condensed, TextLook.Shadow, TextMotion.Drop, 0xFFFF9F1C.toInt(), 48, 0.45f, TextExit.Rise,
        extra = { it.copy(fill = TextFill(listOf(0xFFFFD166.toInt(), 0xFFFF6B6B.toInt()), 90f), stroke = TextStroke(0xFF1B1B3A.toInt(), 0.12f), outline2 = TextStroke(0xFFFFD166.toInt(), 0.08f)) });

    /** This title's style, on its own. */
    val style: TextStyleSpec
        get() = extra(look.applied(TextStyleSpec(font = font, colorArgb = colorArgb, sizeSp = sizeSp)))
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
    Mono("Mono", TextLook.Box.applied(TextStyleSpec(font = TextFont.Mono, letterSpacing = 0.08f))),

    // Reels: the word being said stands out, a few words at a time (EDGE_PLAN E2).
    Hype("Hype", reel(WordStyle(WordMode.Pop, WordStyle.HIGHLIGHT_YELLOW, 3))),
    Karaoke("Karaoke", reel(WordStyle(WordMode.Karaoke, WordStyle.HIGHLIGHT_YELLOW, 0), size = 30)),
    GreenBox("Green box", reel(WordStyle(WordMode.Box, 0xFF22C55E.toInt(), 3))),
    YellowBox("Yellow box", reel(WordStyle(WordMode.Box, WordStyle.HIGHLIGHT_YELLOW, 3), stroke = false)),
    OneWord("One word", reel(WordStyle(WordMode.One, perScreen = 1), size = 48)),
    OneWordPop("One word pop", reel(WordStyle(WordMode.One, perScreen = 1), size = 52, colour = WordStyle.HIGHLIGHT_YELLOW, font = TextFont.Condensed)),
    TwoWords("Two at a time", reel(WordStyle(WordMode.Highlight, 0xFF5CE1E6.toInt(), 2), size = 40)),
    Gamer("Gamer", reel(WordStyle(WordMode.Pop, 0xFF7CFF6B.toInt(), 3), font = TextFont.Condensed)),
    PinkPop("Pink pop", reel(WordStyle(WordMode.Pop, 0xFFFF4FA3.toInt(), 3))),
    RedLine("Red word", reel(WordStyle(WordMode.Highlight, 0xFFFF3B30.toInt(), 4), size = 34)),
    Purple("Purple box", reel(WordStyle(WordMode.Box, 0xFF8B5CF6.toInt(), 3))),
    Orange("Orange fill", reel(WordStyle(WordMode.Karaoke, 0xFFFF9F1C.toInt(), 5), size = 34)),
    Podcast("Podcast", TextLook.Box.applied(TextStyleSpec(font = TextFont.Sans, sizeSp = 28, words = WordStyle(WordMode.Highlight, WordStyle.HIGHLIGHT_YELLOW, 6)))),
    NeonWord("Neon word", TextLook.Neon.applied(TextStyleSpec(font = TextFont.Bold, sizeSp = 38, colorArgb = WHITE, words = WordStyle(WordMode.Highlight, 0xFF5CE1E6.toInt(), 3)))),
    Typed("Typed out", TextLook.Shadow.applied(TextStyleSpec(font = TextFont.Mono, sizeSp = 30, words = WordStyle(WordMode.Karaoke, 0xFF7CFF6B.toInt(), 4)))),
    Clean("Clean", TextLook.Shadow.applied(TextStyleSpec(font = TextFont.Sans, sizeSp = 34, words = WordStyle(WordMode.Highlight, 0xFFB3B3B3.toInt(), 3)))),
    PillWord("Pill word", TextStyleSpec(font = TextFont.Bold, sizeSp = 34, colorArgb = BLACK,
        background = TextBackground(WHITE, 0.92f, 1f, TextBubble.Pill), words = WordStyle(WordMode.Highlight, 0xFFFF3B30.toInt(), 3)))
}

/** A bold, heavily outlined reel caption with [words]: what the reel presets share. */
private fun reel(
    words: WordStyle,
    size: Int = 40,
    colour: Int = WHITE,
    font: TextFont = TextFont.Bold,
    stroke: Boolean = true
): TextStyleSpec = TextStyleSpec(
    font = font,
    colorArgb = colour,
    sizeSp = size,
    stroke = if (stroke) TextStroke(BLACK, 0.22f) else TextStroke.NONE,
    shadow = if (stroke) TextShadow.NONE else TextLook.SHADOW,
    words = words
)

/**
 * The voice effects live with the clip now (timeline/VoiceEffect.kt); the
 * name here keeps every panel that spoke of them working unchanged.
 */
typealias VoiceEffect = com.squish.app.timeline.VoiceEffect

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
