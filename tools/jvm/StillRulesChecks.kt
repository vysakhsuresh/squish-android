import com.squish.app.editor.StillRules
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipTrimmed
import kotlin.system.exitProcess

/**
 * A photo's length on the main track, executed: how far its tail may go past
 * its rendering, which clips are owed a longer rendering once the finger
 * lifts, and how long that rendering is made.
 */
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

private const val RENDERED = 10_000L

private fun still(id: String, outMs: Long, fileMs: Long = RENDERED, layer: Int = 0) = Clip(
    id = id,
    kind = ClipKind.Video,
    label = "Photo",
    sourceInMs = 0L,
    sourceOutMs = outMs,
    timelineStartMs = 0L,
    sourceDurationMs = fileMs,
    layer = layer
)

fun main() {
    // ---- The rendering that covers a tail --------------------------------------
    check("a tail within the rendering keeps it", StillRules.renderLengthFor(4_000L, RENDERED) == RENDERED)
    check("a tail at the rendering's end is covered by it", StillRules.renderLengthFor(RENDERED, RENDERED) == RENDERED)
    check("a tail just past the rendering grows to the first step", StillRules.renderLengthFor(RENDERED + 1L, RENDERED) == StillRules.STEP_MS)
    check("a tail just past a step takes the next", StillRules.renderLengthFor(StillRules.STEP_MS + 1L, RENDERED) == 2 * StillRules.STEP_MS)
    check("a tail on a step takes that step", StillRules.renderLengthFor(2 * StillRules.STEP_MS, RENDERED) == 2 * StillRules.STEP_MS)
    check("never past the most a photo may run", StillRules.renderLengthFor(StillRules.MAX_MS + 5_000L, RENDERED) == StillRules.MAX_MS)
    check("a rendering already longer than the most is kept", StillRules.renderLengthFor(1_000L, StillRules.MAX_MS + 60_000L) == StillRules.MAX_MS + 60_000L)
    check("the rendering always covers the tail", (1..40).all { i ->
        val out = i * 17_000L
        StillRules.renderLengthFor(out, RENDERED) >= minOf(out, StillRules.MAX_MS)
    })

    // ---- Which clips are owed one --------------------------------------------------
    val within = still("a", 8_000L)
    val past = still("b", 25_000L)
    val overlayPast = still("c", 25_000L, layer = 1)
    check("within its file, nothing is owed", !StillRules.outrunsFile(within))
    check("past its file, a rendering is owed", StillRules.outrunsFile(past))
    check("a file of unknown length is never outrun", !StillRules.outrunsFile(still("d", 25_000L, fileMs = 0L)))
    val owed = StillRules.outrunning(listOf(within, past, overlayPast))
    check("only the main-track clip past its file is listed", owed.map { it.id } == listOf("b"))

    // ---- The strip's own limit ----------------------------------------------------
    val timeline = TimelineState(clips = listOf(still("p", 8_000L)))
    val held = timeline.withClipTrimmed("p", 0L, 30_000L).clips.first()
    check("with no ceiling given, the tail stops at the file (${held.sourceOutMs})", held.sourceOutMs == RENDERED)
    val freed = timeline.withClipTrimmed("p", 0L, 30_000L, maxOutMs = StillRules.MAX_MS).clips.first()
    check("with the photo's ceiling, the tail goes past the file (${freed.sourceOutMs})", freed.sourceOutMs == 38_000L)
    val capped = timeline.withClipTrimmed("p", 0L, StillRules.MAX_MS * 2, maxOutMs = StillRules.MAX_MS).clips.first()
    check("...and stops at the photo's ceiling", capped.sourceOutMs == StillRules.MAX_MS)
    check("the head is unaffected", freed.sourceInMs == 0L)
    val backIn = TimelineState(clips = listOf(freed)).withClipTrimmed("p", 0L, -35_000L, maxOutMs = StillRules.MAX_MS).clips.first()
    check("dragged back in, a clip is never shorter than the least", backIn.sourceOutMs == maxOf(3_000L, MIN_CLIP_MS))
    check("after a longer rendering lands, the tail is within its file", !StillRules.outrunsFile(freed.copy(sourceDurationMs = StillRules.renderLengthFor(freed.sourceOutMs, RENDERED))))

    if (failures.isEmpty()) {
        println("PASS - a photo's length on the strip holds: the ceiling, what is owed a longer rendering, and how long it is made")
    } else {
        println("FAIL (${failures.size})")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
