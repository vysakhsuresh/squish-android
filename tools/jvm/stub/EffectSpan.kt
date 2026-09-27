package com.squish.app.timeline

/**
 * Stub for the JVM harness.
 *
 * The real one lives in TimelineEditor.kt, which is a Compose file and cannot be
 * compiled here - it carries an ImageVector and a Color, neither of which the
 * timeline's own arithmetic ever reads. Only the shape the model refers to is
 * needed, and if the real one gains a field the model uses, this fails to
 * compile rather than passing quietly.
 */
data class EffectSpan(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long
)
