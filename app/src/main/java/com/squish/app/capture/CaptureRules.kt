package com.squish.app.capture

/**
 * The in-app camera's arithmetic (EDGE_PLAN G5), apart from the camera so it
 * can be executed: how far the teleprompter has scrolled, what the count-in
 * shows, and where the 9:16 guide sits on the preview.
 */
object CaptureRules {

    /** A talking pace: 150 words a minute is a broadcast read; the slider runs either side. */
    const val DEFAULT_WPM = 140
    const val MIN_WPM = 60
    const val MAX_WPM = 240

    /** Seconds counted down before the take starts. */
    const val COUNT_IN = 3

    /**
     * How far the script has scrolled after [elapsedMs] of reading at [wpm]: a
     * line's worth of pixels for each line's worth of words read. The words on
     * a line are the script's own average ([totalWords] over [lineCount]), so a
     * script of short lines moves quicker than one of long ones at the same
     * pace, as reading it would. Never past the last line.
     */
    fun scrollPx(elapsedMs: Long, wpm: Int, totalWords: Int, lineCount: Int, lineHeightPx: Float): Float {
        if (totalWords <= 0 || lineCount <= 0 || lineHeightPx <= 0f || elapsedMs <= 0L) return 0f
        val wordsPerLine = totalWords.toFloat() / lineCount
        val words = elapsedMs / 60_000f * wpm.coerceIn(MIN_WPM, MAX_WPM)
        val lines = words / wordsPerLine
        return (lines * lineHeightPx).coerceAtMost(lineCount * lineHeightPx)
    }

    /** The words in a script, as a reader counts them. */
    fun wordsIn(script: String): Int = script.split(Regex("\\s+")).count { it.isNotBlank() }

    /** The number the count-in shows [elapsedMs] after it began, or null once the take has started. */
    fun countIn(elapsedMs: Long, from: Int = COUNT_IN): Int? {
        if (elapsedMs < 0L) return from
        val shown = from - (elapsedMs / 1000L).toInt()
        return shown.takeIf { it >= 1 }
    }

    /** A box, left, top, width and height, in the preview's pixels. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * The largest box of [aspect] (width over height) that fits a preview of
     * [width] by [height], centred: what a 9:16 post keeps of a landscape or a
     * taller-than-9:16 frame.
     */
    fun guide(width: Float, height: Float, aspect: Float = 9f / 16f): Box {
        if (width <= 0f || height <= 0f || aspect <= 0f) return Box(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
        val w = minOf(width, height * aspect)
        val h = w / aspect
        return Box((width - w) / 2f, (height - h) / 2f, w, h)
    }

    /** A take's length as the record button shows it, m:ss. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}
