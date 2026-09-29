package com.squish.app.editor

import com.squish.app.timeline.Clip

/**
 * How long a photo may run on the main track, and how long its clip is
 * rendered to cover it.
 *
 * A photo on the main track is a short video rendered from the picture
 * (StillClips), and its tail used to stop at that rendering's end - ten
 * seconds, whatever the picture was for. The export writes the picture
 * itself for however long the clip runs, so the rendering is the one thing
 * holding the strip back; now the tail goes to [MAX_MS], and when it is let
 * go past the rendering's end the clip is rendered again, longer, and swapped
 * in (ClipEdits.trimEnded). Rendered in steps of [STEP_MS], so a tail nudged
 * out a second at a time is not a render a second.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/StillRulesChecks.kt).
 */
object StillRules {

    /** Ten minutes: longer than any slideshow's slide, short enough that the render behind it is not minutes itself. */
    const val MAX_MS = 10 * 60_000L

    /** The rendering grows by half-minutes, from the rendered still's own length. */
    const val STEP_MS = 30_000L

    /**
     * The length to render a photo's clip at so it covers a tail let go at
     * [sourceOutMs]: the next whole step at or past it, never shorter than
     * [renderedMs], the length stills are made at.
     */
    fun renderLengthFor(sourceOutMs: Long, renderedMs: Long): Long {
        val wanted = sourceOutMs.coerceIn(0L, MAX_MS)
        if (wanted <= renderedMs) return renderedMs
        val steps = (wanted + STEP_MS - 1) / STEP_MS
        return (steps * STEP_MS).coerceIn(renderedMs, maxOf(renderedMs, MAX_MS))
    }

    /** Whether [clip]'s tail has been let go past the end of the file it plays from. */
    fun outrunsFile(clip: Clip): Boolean =
        clip.sourceDurationMs > 0L && clip.sourceOutMs > clip.sourceDurationMs

    /**
     * The main-track clips whose files no longer cover them - the ones a
     * longer rendering is owed to once the finger lifts.
     */
    fun outrunning(clips: List<Clip>): List<Clip> = clips.filter { it.isMain && outrunsFile(it) }
}
