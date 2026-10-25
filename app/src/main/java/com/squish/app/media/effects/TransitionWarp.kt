package com.squish.app.media.effects

import com.squish.app.editor.FxParams
import com.squish.app.media.ExportPlan

/**
 * A transition's warp (ExportPlan.Warp) laid onto the effects pass's numbers,
 * so the stages that already ripple, blur towards the middle, pixelate, break
 * into blocks, pull colours apart and mirror for the effects library do it
 * for a join too (EDGE_PLAN F3). One pass, one program, in the preview's
 * surface and in the shot's chain in the file.
 *
 * Each knob the wider of the two, as two blurs are one blur: a placed Ripple
 * under a Ripple join is one ripple, not a doubled one.
 */
object TransitionWarp {
    fun merged(base: FxParams, w: ExportPlan.Warp): FxParams {
        if (w.isNone) return base
        return base.copy(
            split = maxOf(base.split, w.rgb.coerceIn(0f, MAX_RGB)),
            lensA = base.lensA.copy(z = maxOf(base.lensA.z, w.ripple.coerceIn(0f, 1f))),
            lensB = base.lensB.copy(
                x = if (w.mirror > 0.5f) 1f else base.lensB.x,
                z = maxOf(base.lensB.z, w.zoomBlur.coerceIn(0f, 1f)),
                w = if (w.pixel > 0f) maxOf(base.lensB.w, w.pixel.coerceIn(MIN_PIXEL, MAX_PIXEL)) else base.lensB.w
            ),
            glitch2 = base.glitch2.copy(x = maxOf(base.glitch2.x, w.glitch.coerceIn(0f, 1f)))
        )
    }

    /** The pass's own ranges (EffectRecipeChecks holds the library to them too). */
    const val MAX_RGB = 0.04f
    const val MIN_PIXEL = 0.004f
    const val MAX_PIXEL = 0.06f
}
