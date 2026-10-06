package com.squish.app.timeline

import com.squish.app.editor.EffectKind

/**
 * Stub for the JVM harness.
 *
 * The real one is declared in TimelineEditor.kt, which is a Compose file and
 * cannot be compiled here. Its fields are these fields: it used to carry an
 * ImageVector and a Color as well, and those two values on a model are what put
 * the whole of EditorModels.kt out of reach of this harness - for a glyph and a
 * tint that Glyphs.kt derives from [EffectKind] at draw time anyway.
 *
 * So this is a shape stub of something that now has nothing to hide, and if the
 * real one gains a field the model uses, this fails to compile rather than
 * passing quietly - which is exactly what happened when it was last out of step.
 */
data class EffectSpan(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val kind: EffectKind
)
