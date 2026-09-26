package com.squish.app.editor

import com.squish.app.timeline.Clip
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The effects in the library, each a stretch of the video treated a particular way. */
enum class EffectKind(val label: String) {
    Shake("Shake"),
    Punch("Zoom punch"),
    ZoomIn("Slow zoom"),
    Glitch("Glitch"),
    Flash("Flash"),
    Vhs("VHS"),
    Mono("B&W"),
    Invert("Invert"),
    Blur("Blur"),
    Rainbow("Rainbow")
}

/**
 * One effect over one stretch of the timeline, at a strength from 0 to 1.
 * Stored in timeline time, like captions.
 */
data class TimedEffect(
    val id: String,
    val kind: EffectKind,
    val startMs: Long,
    val endMs: Long,
    val intensity: Float = 0.7f
) {
    /** The same effect in a clip's own source clock, for the preview player that plays that clip. */
    fun shiftedInto(clip: Clip): TimedEffect {
        val delta = clip.sourceInMs - clip.timelineStartMs
        return copy(startMs = startMs + delta, endMs = endMs + delta)
    }
}

/**
 * Everything the effects shader needs for one frame, worked out on the CPU from
 * whichever effects cover that moment. Effects that overlap add together.
 */
data class FxParams(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoom: Float = 1f,
    val split: Float = 0f,
    val glitch: Float = 0f,
    val flash: Float = 0f,
    val mono: Float = 0f,
    val invert: Float = 0f,
    val scan: Float = 0f,
    val noise: Float = 0f,
    val blur: Float = 0f,
    val hue: Float = 0f,
    val timeSec: Float = 0f
) {
    val isIdentity: Boolean
        get() = offsetX == 0f && offsetY == 0f && zoom == 1f && split == 0f && glitch == 0f &&
            flash == 0f && mono == 0f && invert == 0f && scan == 0f && noise == 0f &&
            blur == 0f && hue == 0f

    companion object {
        fun at(effects: List<TimedEffect>, timeMs: Long): FxParams {
            var p = FxParams(timeSec = (timeMs % 100_000L) / 1000f)
            for (e in effects) {
                if (timeMs < e.startMs || timeMs >= e.endMs) continue
                val a = e.intensity.coerceIn(0f, 1f)
                val t = (timeMs - e.startMs) / 1000f
                val span = ((e.endMs - e.startMs) / 1000f).coerceAtLeast(0.001f)
                // Every effect eases in and out over a tenth of a second, so none
                // of them switches on and off with a hard cut.
                val edge = minOf(t, span - t).coerceAtLeast(0f)
                val ramp = (edge / 0.1f).coerceIn(0f, 1f)
                val k = a * ramp
                p = when (e.kind) {
                    EffectKind.Shake -> p.copy(
                        offsetX = p.offsetX + wobble(t, 17.3f) * 0.028f * k,
                        offsetY = p.offsetY + wobble(t, 23.9f + 1.7f) * 0.028f * k,
                        zoom = p.zoom * (1f + 0.06f * k)
                    )
                    EffectKind.Punch -> {
                        // Two hits a second, each a sharp push in that settles.
                        val phase = (t * 2f) % 1f
                        p.copy(zoom = p.zoom * (1f + 0.22f * k * exp(-phase * 7f)))
                    }
                    EffectKind.ZoomIn -> p.copy(zoom = p.zoom * (1f + 0.3f * a * (t / span).coerceIn(0f, 1f)))
                    EffectKind.Glitch -> {
                        // Bursts rather than a steady wobble: glitches read as glitches
                        // because they come and go.
                        val burst = if (sin(t * 11.0 + sin(t * 3.7) * 4.0) > 0.2) 1f else 0.25f
                        p.copy(glitch = p.glitch + k * burst, split = p.split + 0.012f * k * burst)
                    }
                    // Full white at the start of its stretch, fading out - a camera flash.
                    EffectKind.Flash -> p.copy(flash = maxOf(p.flash, a * exp(-t * 4f)))
                    EffectKind.Vhs -> p.copy(scan = p.scan + k, noise = p.noise + 0.12f * k, split = p.split + 0.004f * k)
                    EffectKind.Mono -> p.copy(mono = maxOf(p.mono, k))
                    EffectKind.Invert -> p.copy(invert = maxOf(p.invert, k))
                    EffectKind.Blur -> p.copy(blur = p.blur + 0.012f * k)
                    EffectKind.Rainbow -> p.copy(hue = p.hue + (t * 0.5f % 1f) * 2f * PI.toFloat() * k)
                }
            }
            return p
        }

        /** A smooth, non-repeating shake from two sines at unrelated rates. */
        private fun wobble(t: Float, rate: Float): Float =
            (sin(t * rate) * 0.6 + sin(t * rate * 2.31 + 1.3) * 0.4).toFloat()
    }
}
