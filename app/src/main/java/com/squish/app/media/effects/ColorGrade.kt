@file:OptIn(UnstableApi::class)

package com.squish.app.media.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * Turns a [Grade] into Media3 effects. One implementation, used by both the export
 * pipeline and the preview player - if these two ever built their effects
 * separately, what you saw would stop being what you rendered.
 */
object ColorGrade {

    fun effects(grade: Grade): List<Effect> {
        if (grade.isIdentity) return emptyList()

        // A look with grain, a vignette or bloom in it does the whole grade in one
        // shader pass. Splitting the colour work back out to the built-ins would
        // mean four passes where one will do, over every frame.
        if (grade.needsShader) return listOf(LookEffect(grade))

        val out = mutableListOf<Effect>()

        if (grade.hasChannelGain) {
            out.add(
                RgbAdjustment.Builder()
                    .setRedScale(grade.redScale)
                    .setGreenScale(grade.greenScale)
                    .setBlueScale(grade.blueScale)
                    .build()
            )
        }
        if (grade.hasContrast) out.add(Contrast(grade.contrast))
        if (grade.hasSaturation) {
            out.add(HslAdjustment.Builder().adjustSaturation(grade.saturation * 100f).build())
        }
        return out
    }
}
