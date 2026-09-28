import com.squish.app.editor.OutputSize
import com.squish.app.media.ExportPresets

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

/**
 * The shape after the user's rotation, which is what everything downstream of it
 * in the render chain has to measure against. Mirrors EditorUiState.framedWidth /
 * framedHeight, which cannot be used here because the state drags the framework
 * in with it.
 */
private fun framed(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees % 180 != 0) height to width else width to height

/** The shapes phones actually shoot. */
private val sources = listOf(
    "portrait 1080x1920" to (1080 to 1920),
    "landscape 1920x1080" to (1920 to 1080),
    "4K landscape" to (3840 to 2160),
    "4K portrait" to (2160 to 3840),
    "square" to (1080 to 1080),
    "odd 1079x1921" to (1079 to 1921)
)

fun main() {
    for ((name, size) in sources) {
        val (w, h) = size
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (fw, fh) = framed(w, h, rotation)

            // A quarter turn transposes the frame. Half a turn does not. If this
            // is wrong the picture is asked to fit a box the wrong way round.
            if (rotation % 180 != 0) {
                check("$name at $rotation transposes", fw == h && fh == w)
            } else {
                check("$name at $rotation keeps its shape", fw == w && fh == h)
            }

            for (outputP in listOf(OutputSize.ORIGINAL) + OutputSize.PRESETS) {
                val target = ExportPresets.resolutionFor(outputP, fw, fh)

                // The crucial one: the output box has to be the same way round as
                // the frame it is fitting. A landscape frame given a portrait box
                // is the letterboxed, wrong-shaped export that rotating produced.
                check(
                    "$name at $rotation, ${outputP}p: box is the same way round as the frame " +
                        "(frame ${fw}x$fh, box ${target.width}x${target.height})",
                    (fw >= fh) == (target.width >= target.height)
                )

                // And the same shape, to within the rounding to even numbers.
                if (target.width > 0 && target.height > 0 && fh > 0) {
                    val sourceAspect = fw.toFloat() / fh
                    val targetAspect = target.width.toFloat() / target.height
                    check(
                        "$name at $rotation, ${outputP}p keeps its aspect " +
                            "($sourceAspect vs $targetAspect)",
                        kotlin.math.abs(sourceAspect - targetAspect) < 0.02f
                    )
                }

                // Encoders reject odd dimensions on some devices and not others,
                // which is the worst kind of failure to chase.
                check(
                    "$name at $rotation, ${outputP}p is even",
                    target.width % 2 == 0 && target.height % 2 == 0
                )

                check("$name at $rotation, ${outputP}p is not empty", target.width > 0 && target.height > 0)
            }
        }
    }

    // Measuring against the source instead of the rotated frame really does go
    // wrong, or the fix above is guarding nothing.
    val naive = ExportPresets.resolutionFor(720, 1080, 1920)
    val (rotW, rotH) = framed(1080, 1920, 90)
    check(
        "the old way really did put a landscape frame in a portrait box",
        (rotW >= rotH) != (naive.width >= naive.height)
    )
    println("a rotated 1080x1920 at 720p: frame ${rotW}x$rotH, " +
        "old box ${naive.width}x${naive.height}, new box " +
        ExportPresets.resolutionFor(720, rotW, rotH).let { "${it.width}x${it.height}" })

    // The canvas is the crop, at the chosen size. A 9:16 cut of a landscape clip
    // exported at 720p is a 720x1280 file - not the cut fitted into 1280x720
    // with black down both sides, which is what sizing the whole frame produced.
    for ((name, size) in sources) {
        val (w, h) = size
        for (rotation in intArrayOf(0, 90)) {
            val (fw, fh) = framed(w, h, rotation)
            val frameAspect = fw.toFloat() / fh
            for (ratio in listOf(9f / 16f, 1f, 4f / 5f, 16f / 9f)) {
                val crop = com.squish.app.editor.CropRect.centred(ratio, frameAspect)
                for (outputP in listOf(OutputSize.ORIGINAL) + OutputSize.PRESETS) {
                    val canvas = ExportPresets.canvasFor(outputP, fw, fh, crop.width, crop.height)
                    val got = canvas.width.toFloat() / canvas.height
                    check(
                        "$name at $rotation, crop $ratio, ${outputP}p: canvas ${canvas.width}x${canvas.height} is the crop's shape",
                        kotlin.math.abs(got - ratio) / ratio < 0.02f
                    )
                    check("$name crop $ratio ${outputP}p is even", canvas.width % 2 == 0 && canvas.height % 2 == 0)
                    if (outputP != OutputSize.ORIGINAL) {
                        check(
                            "$name crop $ratio ${outputP}p: short edge ${minOf(canvas.width, canvas.height)}",
                            kotlin.math.abs(minOf(canvas.width, canvas.height) - outputP) <= 2
                        )
                    } else {
                        // Original is the kept pixels, never scaled up past them.
                        check("$name crop $ratio at Original is not upscaled", canvas.width <= fw && canvas.height <= fh)
                    }
                }
            }
        }
    }
    val nineSixteen = com.squish.app.editor.CropRect.centred(9f / 16f, 16f / 9f)
    val reel = ExportPresets.canvasFor(720, 1920, 1080, nineSixteen.width, nineSixteen.height)
    check("9:16 of 1920x1080 at 720p is 720x1280 (got ${reel.width}x${reel.height})", reel.width == 720 && reel.height == 1280)
    val whole = ExportPresets.canvasFor(720, 1920, 1080, 1f, 1f)
    check("no crop is the old size", whole == ExportPresets.resolutionFor(720, 1920, 1080))

    // A cropped frame costs fewer bits than the whole one at the same size.
    val full = ExportPresets.bitrateFor(OutputSize.ORIGINAL, 1920, 1080, 30f, 16_000_000L)
    val cut = ExportPresets.bitrateForFrame(
        ExportPresets.canvasFor(OutputSize.ORIGINAL, 1920, 1080, nineSixteen.width, nineSixteen.height),
        1920, 1080, 30f, 16_000_000L
    )
    check("a 9:16 cut is budgeted less than the whole frame ($cut vs $full)", cut < full / 2)

    // Nothing measured yet must not produce a box of zero, which Media3 rejects.
    for (outputP in listOf(OutputSize.ORIGINAL) + OutputSize.PRESETS) {
        val none = ExportPresets.resolutionFor(outputP, 0, 0)
        check("${outputP}p survives an unmeasured source", none.width <= 0 || none.height > 0)
    }

    if (failures.isEmpty()) {
        println("PASS - the output box is always the same shape and the same way round as the rotated frame")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
