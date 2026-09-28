import com.squish.app.editor.PreviewBox
import kotlin.math.abs

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

/** The shapes real footage actually arrives in. */
private val shapes = mapOf(
    "portrait 9:16" to 9f / 16f,
    "portrait 3:4" to 3f / 4f,
    "square" to 1f,
    "landscape 4:3" to 4f / 3f,
    "landscape 16:9" to 16f / 9f,
    "cinemascope 2.39:1" to 2.39f,
    "phone-wide 20:9" to 20f / 9f
)

/** Narrow phone, ordinary phone, large phone, tablet. */
private val widths = floatArrayOf(280f, 336f, 384f, 700f)

fun main() {
    for ((name, aspect) in shapes) {
        for (widthDp in widths) {
            val boxHeight = PreviewBox.heightDp(aspect, widthDp)

            check(
                "$name at ${widthDp}dp stays within its bounds",
                boxHeight >= PreviewBox.MIN_HEIGHT_DP && boxHeight <= PreviewBox.MAX_HEIGHT_DP
            )

            val (w, h) = PreviewBox.fittedSizeDp(aspect, widthDp, boxHeight)

            // The whole point: every pixel of the frame is inside the box. If this
            // fails the editor is hiding part of the shot, which is what it was
            // doing to every portrait clip.
            check("$name at ${widthDp}dp fits inside its box", w <= widthDp + 0.01f && h <= boxHeight + 0.01f)

            // And it is still the right shape - fitting by squashing would also
            // "fit", and would be a different kind of lie.
            check(
                "$name at ${widthDp}dp keeps its shape",
                h > 0f && abs(w / h - aspect) < 0.01f
            )

            // Nothing is wasted: the picture touches at least one pair of edges,
            // or the box is bigger than it needs to be.
            val touchesWidth = abs(w - widthDp) < 0.01f
            val touchesHeight = abs(h - boxHeight) < 0.01f
            check("$name at ${widthDp}dp uses the room it was given", touchesWidth || touchesHeight)
        }
    }

    // The shapes that used to break it, spelled out.
    val portraitBox = PreviewBox.heightDp(9f / 16f, 336f)
    val (pw, ph) = PreviewBox.fittedSizeDp(9f / 16f, 336f, portraitBox)
    check("a portrait clip is shown whole", pw <= 336f && ph <= portraitBox)
    check("a portrait clip is taller than it is wide", ph > pw)
    println("portrait 9:16 in a 336dp-wide editor: box %.0fdp tall, picture %.0f x %.0f".format(portraitBox, pw, ph))

    val wideBox = PreviewBox.heightDp(2.39f, 336f)
    check("a cinemascope clip is not reduced to a slot", wideBox >= PreviewBox.MIN_HEIGHT_DP)
    println("cinemascope 2.39:1 in a 336dp-wide editor: box %.0fdp tall".format(wideBox))

    // Degenerate input must not produce a NaN height, which reaches the layout as
    // something far stranger than a wrong number.
    check("a zero aspect falls back", PreviewBox.heightDp(0f, 336f).isFinite())
    check("a negative aspect falls back", PreviewBox.heightDp(-2f, 336f).isFinite())
    check("a NaN aspect falls back", PreviewBox.heightDp(Float.NaN, 336f).isFinite())
    check("a zero width does not divide by zero", PreviewBox.heightDp(1.78f, 0f).isFinite())
    val (zw, zh) = PreviewBox.fittedSizeDp(1.78f, 0f, 0f)
    check("a zero box has no picture in it", zw == 0f && zh == 0f)

    // ---- Rotation on screen ----------------------------------------------------
    // The case that froze the editor on the phone: a 640x480 clip, Rotate 90. The
    // canvas takes the rotated shape; the view laid out inside it must cover it
    // exactly once turned, or the picture sits small in a corner.
    for (degrees in intArrayOf(0, 90, 180, 270, -90, 360, 450)) {
        val turned = PreviewBox.isQuarterTurn(degrees)
        val canvasW = if (turned) 480f else 640f
        val canvasH = if (turned) 640f else 480f
        val (vw, vh) = PreviewBox.unrotatedSize(canvasW, canvasH, degrees)
        check("rotation $degrees: the view has the source's own shape", abs(vw / vh - 640f / 480f) < 0.001f)
        // A view turned a quarter swaps its bounding box.
        val (bw, bh) = if (turned) vh to vw else vw to vh
        check("rotation $degrees: turned, it covers the canvas exactly", abs(bw - canvasW) < 0.01f && abs(bh - canvasH) < 0.01f)
    }
    check("the screen turns the export's way", PreviewBox.screenRotation(90) == -90f)

    // ---- A layer's own shape --------------------------------------------------
    val portrait = PreviewBox.displayAspect(1080, 1920, 0, 1f)!!
    check("a portrait layer is portrait", abs(portrait - 0.5625f) < 0.001f)
    val sideways = PreviewBox.displayAspect(1920, 1080, 90, 1f)!!
    check("rotation the decoder left undone is applied", abs(sideways - 0.5625f) < 0.001f)
    check("non-square pixels are corrected", abs(PreviewBox.displayAspect(1440, 1080, 0, 4f / 3f)!! - 16f / 9f) < 0.001f)
    check("no size, no shape", PreviewBox.displayAspect(0, 0, 0, 1f) == null)
    // Fitted into a landscape canvas: the portrait reaction clip gets a column,
    // with no black bars inside its own box.
    val (lw, lh) = PreviewBox.fittedSizeDp(portrait, 1920f, 1080f)
    check("a portrait layer fits a landscape canvas as a column", abs(lw - 607.5f) < 0.5f && abs(lh - 1080f) < 0.5f)

    // ---- The kept frame ---------------------------------------------------------
    val full = PreviewBox.cropFrame(16f / 9f, null)
    check("no crop keeps everything", full == PreviewBox.Frame())
    val square = PreviewBox.cropFrame(16f / 9f, 1f)
    check("1:1 of 16:9 keeps the middle", abs(square.width - 0.5625f) < 0.001f && abs(square.left - 0.21875f) < 0.001f)
    check("1:1 keeps full height", square.top == 0f && square.bottom == 1f)
    val tall = PreviewBox.cropFrame(1f, 16f / 9f)
    check("a wide crop of a square keeps full width", tall.left == 0f && abs(tall.height - 0.5625f) < 0.001f)
    val followed = PreviewBox.cropFrame(16f / 9f, 1f, focus = 0.95f to 0.5f)
    check("auto-reframe slides the crop but not off the frame", abs(followed.right - 1f) < 0.001f)
    check("a nonsense ratio keeps everything", PreviewBox.cropFrame(16f / 9f, Float.NaN) == PreviewBox.Frame())

    if (failures.isEmpty()) {
        println("PASS - every shape of footage is shown whole, and shown as large as it can be")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
