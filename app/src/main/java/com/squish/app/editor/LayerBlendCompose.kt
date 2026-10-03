package com.squish.app.editor

import androidx.compose.ui.graphics.BlendMode
import com.squish.app.timeline.LayerBlend

/**
 * The preview's half of a blend mode.
 *
 * `LayerBlend` holds the formulas and lives in `timeline/`, which carries
 * nothing Android-shaped so the suites can execute it. Compose's own
 * `BlendMode` is Skia's implementation of the same W3C separable functions, so
 * naming the matching one here is all the preview needs - and the `when` is
 * exhaustive, so a mode added to the enum will not compile until it is given
 * one.
 *
 * The file's half is `squish_layer_blend_es2.glsl`, which mirrors the formulas
 * directly; `LayerBlendChecks` makes sure it has a branch for every mode.
 */
val LayerBlend.composeMode: BlendMode
    get() = when (this) {
        LayerBlend.Normal -> BlendMode.SrcOver
        LayerBlend.Multiply -> BlendMode.Multiply
        LayerBlend.Screen -> BlendMode.Screen
        LayerBlend.Overlay -> BlendMode.Overlay
        LayerBlend.Darken -> BlendMode.Darken
        LayerBlend.Lighten -> BlendMode.Lighten
        LayerBlend.HardLight -> BlendMode.Hardlight
        LayerBlend.SoftLight -> BlendMode.Softlight
        LayerBlend.Difference -> BlendMode.Difference
        LayerBlend.Add -> BlendMode.Plus
    }
