@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi

/**
 * Turns a [Grade] into Media3 effects for the export.
 *
 * Every grade goes through the look shader, the one the preview runs on every
 * surface (LiveLookEffect is the same program reading a live value). The simpler
 * looks used to be handed to Media3's built-in colour effects instead, on the
 * grounds that they were cheaper - but HslAdjustment scales saturation in HSL
 * space and the shader mixes against luma, which are different curves: a Vivid
 * red came out another red in the file than on screen, for fourteen of the looks
 * and for the Saturation slider on its own. One pass of the same maths is the
 * only way "what you see is what you render" is true rather than approximately
 * true.
 */
object ColorGrade {

    fun effects(grade: Grade): List<Effect> =
        if (grade.isIdentity) emptyList() else listOf(LookEffect(grade))
}
