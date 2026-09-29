import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRatio
import com.squish.app.editor.CropRect
import com.squish.app.editor.CropRules
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

// A clip's own crop (B12), executed: the zoom that keeps a turned picture
// covering its frame, the window's shape, the fit into the canvas, and - the
// one that matters - that the export shader's maths (sourcePoint) and the
// preview's layers (windowPoint) are inverses of each other, so what is kept
// on screen is what is kept in the file.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

fun main() {
    // --- Zoom to cover: nothing at no turn, the corner's reach at 45 degrees. ---
    check(near(CropRules.zoomToCover(0f, 16f / 9f), 1f), "an unturned picture is zoomed")
    val at45 = (cos(Math.toRadians(45.0)) + sin(Math.toRadians(45.0)) * 16.0 / 9.0).toFloat()
    check(near(CropRules.zoomToCover(45f, 16f / 9f), at45, 1e-3f), "45 degrees on 16:9 is not ${at45}x: ${CropRules.zoomToCover(45f, 16f / 9f)}")
    check(near(CropRules.zoomToCover(-30f, 1f), CropRules.zoomToCover(30f, 1f)), "the zoom is not the same either way")
    check(near(CropRules.zoomToCover(30f, 9f / 16f), CropRules.zoomToCover(30f, 16f / 9f)), "a portrait frame zooms differently from a landscape one")
    // And it does cover: every point of a full window reads inside the source.
    for (degrees in listOf(-45f, -20f, -5f, 0f, 7f, 30f, 45f)) {
        for (aspect in listOf(9f / 16f, 1f, 4f / 3f, 16f / 9f, 2.35f)) {
            val crop = ClipCrop(straightenDegrees = degrees)
            for (ox in listOf(0f, 0.25f, 0.5f, 1f)) for (oy in listOf(0f, 0.5f, 0.75f, 1f)) {
                val (sx, sy) = CropRules.sourcePoint(ox, oy, crop, aspect)
                check(sx >= -1e-3f && sx <= 1f + 1e-3f && sy >= -1e-3f && sy <= 1f + 1e-3f,
                    "a full window at $degrees on $aspect reads outside the source at ($ox,$oy): ($sx,$sy)")
            }
        }
    }

    // --- The two maps are inverses, whatever the window, turn, flip and shape. ---
    val windows = listOf(CropRect(), CropRect.of(0.1f, 0.2f, 0.7f, 0.9f), CropRect.of(0.3f, 0.3f, 0.5f, 0.6f), CropRect.of(0f, 0.4f, 1f, 0.6f))
    for (rect in windows) for (degrees in listOf(0f, 12f, -33f)) for (flipH in listOf(false, true)) for (flipV in listOf(false, true)) {
        for (aspect in listOf(9f / 16f, 16f / 9f, 1f)) {
            val crop = ClipCrop(rect, degrees, flipH, flipV)
            for (ox in listOf(0f, 0.3f, 0.5f, 0.9f)) for (oy in listOf(0.1f, 0.5f, 1f)) {
                val (sx, sy) = CropRules.sourcePoint(ox, oy, crop, aspect)
                val (bx, by) = CropRules.windowPoint(sx, sy, crop, aspect)
                check(near(bx, ox, 1e-3f) && near(by, oy, 1e-3f), "$crop on $aspect: ($ox,$oy) -> ($sx,$sy) -> ($bx,$by)")
            }
        }
    }

    // --- No crop reads the source as it is; a flip reads it mirrored. ------------
    run {
        val none = ClipCrop()
        val (sx, sy) = CropRules.sourcePoint(0.2f, 0.7f, none, 16f / 9f)
        check(near(sx, 0.2f) && near(sy, 0.7f), "an identity crop moved a point: ($sx,$sy)")
        check(none.isIdentity && !ClipCrop(flipHorizontal = true).isIdentity && !ClipCrop(straightenDegrees = 1f).isIdentity, "isIdentity is wrong")
        val mirrored = CropRules.sourcePoint(0f, 0.5f, ClipCrop(flipHorizontal = true), 16f / 9f)
        check(near(mirrored.first, 1f) && near(mirrored.second, 0.5f), "the left of a mirrored window is not the right of the source: $mirrored")
        val upended = CropRules.sourcePoint(0.5f, 0f, ClipCrop(flipVertical = true), 1f)
        check(near(upended.second, 1f), "the top of a vertically flipped window is not the bottom of the source: $upended")
        // A window on the left of an unturned picture reads the left of the source.
        val left = CropRules.sourcePoint(0f, 0.5f, ClipCrop(CropRect.of(0f, 0f, 0.5f, 1f)), 16f / 9f)
        check(near(left.first, 0f), "the left window reads from ${left.first}")
        val leftRight = CropRules.sourcePoint(1f, 0.5f, ClipCrop(CropRect.of(0f, 0f, 0.5f, 1f)), 16f / 9f)
        check(near(leftRight.first, 0.5f), "the left window's right edge reads from ${leftRight.first}")
    }

    // --- Straighten is clockwise as seen: the top of the window came from left of the source's top. ---
    run {
        val (sx, _) = CropRules.sourcePoint(0.5f, 0f, ClipCrop(straightenDegrees = 10f), 16f / 9f)
        check(sx < 0.5f, "a clockwise turn read the window's top from the right: $sx")
        val (rx, _) = CropRules.sourcePoint(1f, 0.5f, ClipCrop(straightenDegrees = 10f), 16f / 9f)
        check(rx < 1f, "at 10 degrees the right edge is not pulled in by the zoom: $rx")
    }

    // --- Shapes and fits. -------------------------------------------------------------
    run {
        check(near(CropRules.croppedAspect(16f / 9f, ClipCrop(CropRect.of(0.25f, 0f, 0.75f, 1f))), 8f / 9f), "half of 16:9 is not 8:9")
        check(near(CropRules.croppedAspect(16f / 9f, null), 16f / 9f), "no crop changed the shape")
        check(near(CropRules.fitScale(100f, 50f, 200f, 200f), 2f), "fit of 100x50 into 200x200 is not 2")
        check(near(CropRules.fitScale(100f, 50f, 100f, 100f), 1f), "fit of 100x50 into 100x100 is not 1")
        check(near(CropRules.fitScale(50f, 100f, 200f, 100f), 1f), "fit of 50x100 into 200x100 is not 1")
        check(near(CropRules.fitScale(0f, 0f, 200f, 100f), 1f), "an empty region does not fit at 1")
        // A shot's window fits the canvas as the export's Presentation would: a
        // square cut from 16:9 on a 16:9 canvas is scaled by 1/rh = 1.
        val square = CropRules.heldToRatio(CropRect(), 1f, 16f / 9f)
        check(near(square.aspect(16f / 9f), 1f, 1e-2f), "held to 1:1 is ${square.aspect(16f / 9f)}")
        check(near(square.height, 1f, 1e-3f) && near(square.left + square.right, 1f, 1e-3f), "the square is not full height and centred: $square")
        check(near(CropRules.fitScale(1920f * square.width, 1080f * square.height, 1920f, 1080f), 1f), "a full-height square is scaled")
        val wide = CropRules.heldToRatio(CropRect(), 16f / 9f, 16f / 9f)
        check(wide.isFull, "16:9 on a 16:9 frame is not the whole picture: $wide")
        val tall = CropRules.heldToRatio(CropRect.of(0.2f, 0.2f, 0.6f, 0.8f), 9f / 16f, 16f / 9f)
        check(near(tall.aspect(16f / 9f), 9f / 16f, 1e-2f) && tall.left >= 0f && tall.bottom <= 1f, "held to 9:16 is $tall")
        check(CropRules.heldToRatio(CropRect.of(0.1f, 0.1f, 0.4f, 0.4f), null, 1f) == CropRect.of(0.1f, 0.1f, 0.4f, 0.4f), "Free changed the window")
        for (ratio in CropRatio.entries) {
            val held = CropRules.heldToRatio(CropRect(), ratio.value, 4f / 3f)
            check(held.left >= 0f && held.top >= 0f && held.right <= 1f + 1e-4f && held.bottom <= 1f + 1e-4f, "${ratio.label} runs past the frame: $held")
        }
    }

    // --- A handle dragged with the shape held: the other side follows, inside the frame. ---
    run {
        val before = CropRules.heldToRatio(CropRect.of(0.25f, 0.25f, 0.5f, 0.75f), 1f, 1f)
        val free = CropRect.of(before.left, before.top, before.right + 0.2f, before.bottom)
        val held = CropRules.draggedHeld(free, before, 1f, 1f, movesLeft = false, movesRight = true, movesTop = false, movesBottom = false)
        check(near(held.aspect(1f), 1f, 1e-2f), "the right edge dragged left the shape: ${held.aspect(1f)}")
        check(near(held.width, free.width, 1e-3f), "the width did not follow the finger: ${held.width} vs ${free.width}")
        check(near(held.left, before.left, 1e-3f), "the left edge moved when the right was dragged")
        val far = CropRect.of(before.left, before.top, 1f, before.bottom)
        val clamped = CropRules.draggedHeld(CropRect.of(far.left, far.top, 1.5f, far.bottom), before, 1f, 1f, false, true, false, false)
        check(clamped.right <= 1f + 1e-4f && clamped.bottom <= 1f + 1e-4f && near(clamped.aspect(1f), 1f, 1e-2f), "dragged past the frame the window left it: $clamped")
        val corner = CropRules.draggedHeld(CropRect.of(0.1f, 0.1f, before.right, before.bottom), before, 2f, 1f, true, false, true, false)
        check(near(corner.aspect(1f), 2f, 1e-2f) && near(corner.right, before.right, 1e-3f) && near(corner.bottom, before.bottom, 1e-3f),
            "a corner drag moved the anchored sides: $corner")
        val tiny = CropRules.draggedHeld(CropRect.of(before.left, before.top, before.left + 0.01f, before.bottom), before, 1f, 1f, false, true, false, false)
        check(tiny.width >= CropRect.MIN_SIDE - 1e-3f, "the window collapsed: $tiny")
    }

    println("clip crop: zoom, window, fit, and the shader against the layers")
    if (problems.isEmpty()) println("PASS - the preview's layers and the export's shader keep the same part of the picture")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
