import com.squish.app.editor.CropRect
import kotlin.math.abs

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

private fun near(a: Float, b: Float, slack: Float = 0.002f) = abs(a - b) < slack

fun main() {
    check("the default keeps everything", CropRect().isFull)
    check("the default is the whole width", near(CropRect().width, 1f))

    // Every gesture a handle can make, including the ones that are nonsense.
    val absurd = listOf(
        listOf(-5f, -5f, 5f, 5f),
        listOf(0.9f, 0.9f, 0.1f, 0.1f),     // dragged past each other
        listOf(0.5f, 0.5f, 0.5f, 0.5f),     // collapsed to nothing
        listOf(0f, 0f, 0.001f, 0.001f),     // a sliver
        listOf(1f, 1f, 1f, 1f),             // entirely off the far corner
        listOf(Float.NaN, 0f, 1f, 1f)
    )
    for (values in absurd) {
        val (l, t, r, b) = values
        val rect = CropRect.of(l, t, r, b)
        check(
            "of($l,$t,$r,$b) stays inside the frame",
            rect.left >= -0.001f && rect.top >= -0.001f && rect.right <= 1.001f && rect.bottom <= 1.001f
        )
        check("of($l,$t,$r,$b) keeps a usable width", rect.width >= CropRect.MIN_SIDE - 0.001f)
        check("of($l,$t,$r,$b) keeps a usable height", rect.height >= CropRect.MIN_SIDE - 0.001f)
    }

    // The shape of what is kept. A half-width crop of a 16:9 frame is 8:9.
    val halfWide = CropRect.of(0.25f, 0f, 0.75f, 1f)
    check("half the width of 16:9 is 8:9", near(halfWide.aspect(16f / 9f), (16f / 9f) / 2f))
    check("the whole frame keeps its shape", near(CropRect().aspect(16f / 9f), 16f / 9f))

    // Media3 takes NDC with the vertical axis pointing up. Getting the flip wrong
    // crops the top when you asked for the bottom - a bug that looks like a
    // working feature until someone checks the output.
    val full = CropRect().toNdc()
    check("the full frame is the whole NDC square", near(full[0], -1f) && near(full[1], 1f) && near(full[2], -1f) && near(full[3], 1f))

    val topHalf = CropRect.of(0f, 0f, 1f, 0.5f)
    val ndc = topHalf.toNdc()
    check("keeping the top half gives NDC bottom 0", near(ndc[2], 0f))
    check("keeping the top half gives NDC top 1", near(ndc[3], 1f))

    val bottomHalf = CropRect.of(0f, 0.5f, 1f, 1f)
    val ndcB = bottomHalf.toNdc()
    check("keeping the bottom half gives NDC bottom -1", near(ndcB[2], -1f))
    check("keeping the bottom half gives NDC top 0", near(ndcB[3], 0f))

    val leftHalf = CropRect.of(0f, 0f, 0.5f, 1f)
    val ndcL = leftHalf.toNdc()
    check("keeping the left half gives NDC left -1", near(ndcL[0], -1f))
    check("keeping the left half gives NDC right 0", near(ndcL[1], 0f))

    // Media3 requires left < right and bottom < top. Anything of() can produce
    // must satisfy that, or the export throws.
    for (values in absurd + listOf(listOf(0.2f, 0.3f, 0.8f, 0.9f))) {
        val (l, t, r, b) = values
        val n = CropRect.of(l, t, r, b).toNdc()
        check("NDC from ($l,$t,$r,$b) has left < right", n[0] < n[1])
        check("NDC from ($l,$t,$r,$b) has bottom < top", n[2] < n[3])
    }

    // A fixed ratio expressed as a rectangle: the largest of that shape that fits,
    // centred, so switching to custom starts from what was already on screen.
    for (sourceAspect in floatArrayOf(9f / 16f, 1f, 4f / 3f, 16f / 9f, 2.39f)) {
        for (ratio in floatArrayOf(9f / 16f, 1f, 16f / 9f)) {
            val rect = CropRect.centred(ratio, sourceAspect)
            check(
                "$ratio inside $sourceAspect has that shape",
                near(rect.aspect(sourceAspect), ratio, 0.02f)
            )
            check(
                "$ratio inside $sourceAspect fits",
                rect.left >= -0.001f && rect.right <= 1.001f && rect.top >= -0.001f && rect.bottom <= 1.001f
            )
            check(
                "$ratio inside $sourceAspect is centred",
                near(rect.left + rect.right, 1f, 0.01f) && near(rect.top + rect.bottom, 1f, 0.01f)
            )
            // And it touches the edges - a crop smaller than it needs to be
            // throws away resolution for nothing.
            check(
                "$ratio inside $sourceAspect uses the whole of one axis",
                near(rect.width, 1f, 0.01f) || near(rect.height, 1f, 0.01f)
            )
        }
    }

    check("a zero ratio falls back to everything", CropRect.centred(0f, 1.78f).isFull)
    check("a zero source falls back to everything", CropRect.centred(1f, 0f).isFull)

    if (failures.isEmpty()) {
        println("PASS - a hand-drawn crop stays inside the frame and means the same thing to the renderer")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
