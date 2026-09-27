package com.squish.app.editor

/**
 * Stub for the JVM harness.
 *
 * The real one lives in EditorModels.kt, which cannot be compiled here because it
 * drags the whole framework in with it. Only the constants the sizing arithmetic
 * reads are needed, and a mismatch fails to compile rather than passing quietly.
 */
object OutputSize {
    const val ORIGINAL = 0
    val PRESETS = listOf(360, 480, 720, 1080, 1440, 2160)
    const val MIN_P = 144
    const val MAX_P = 2160
}
