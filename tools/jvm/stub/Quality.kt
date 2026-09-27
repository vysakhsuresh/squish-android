package com.squish.app.editor

/**
 * Stub for the JVM harness.
 *
 * The real one lives in EditorModels.kt, which cannot be compiled here because it
 * drags the whole framework in with it. Only the four names matter to the
 * arithmetic under check, and a mismatch would fail to compile rather than pass
 * quietly.
 */
enum class Quality(val label: String) {
    Small("Small"), Medium("Medium"), High("High"), Original("Original")
}
