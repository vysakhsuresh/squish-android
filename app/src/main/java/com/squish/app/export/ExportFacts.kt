package com.squish.app.export

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The done screen's speed line (EDGE_PLAN I2): how long the file took to make,
 * measured from the moment the render started to the moment it was written,
 * and that against the length of the file - "Made in 7.1 s · 1.4× real time".
 * Nothing estimated: a record with no time measured shows no line at all.
 */
object ExportFacts {

    fun speedLine(renderMs: Long, lengthMs: Long, locale: Locale = Locale.getDefault()): String? {
        if (renderMs <= 0L || lengthMs <= 0L) return null
        val took = when {
            renderMs < 10_000L -> String.format(locale, "%.1f s", renderMs / 1000.0)
            renderMs < 60_000L -> "${(renderMs / 1000.0).roundToInt()} s"
            else -> {
                val s = (renderMs / 1000.0).roundToInt()
                if (s % 60 == 0) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            }
        }
        val x = lengthMs.toDouble() / renderMs
        val times = if (x >= 10) "${x.roundToInt()}" else String.format(locale, "%.1f", x)
        return "Made in $took · $times× real time"
    }
}
