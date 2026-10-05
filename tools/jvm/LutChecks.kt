import com.squish.app.media.effects.CubeFile
import com.squish.app.media.effects.Lut3D
import kotlin.math.abs
import kotlin.system.exitProcess

// Reading a .cube and looking a colour up in it. The format every grading tool
// writes, and the one way a brand's own look arrives.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 3e-3f) = abs(a - b) <= slack

/** A .cube written the way a real one is, for a cube of [size] through [f]. */
private fun cubeText(size: Int, title: String = "test", f: (Float, Float, Float) -> Triple<Float, Float, Float>): String {
    val sb = StringBuilder()
    sb.appendLine("# a comment")
    sb.appendLine("TITLE \"$title\"")
    sb.appendLine("LUT_3D_SIZE $size")
    sb.appendLine()
    val n = size - 1
    for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
        val (r, g, b) = f(x.toFloat() / n, y.toFloat() / n, z.toFloat() / n)
        sb.appendLine("$r $g $b")
    }
    return sb.toString()
}

fun main() {
    // --- An identity cube changes nothing, at the corners and between them. --
    run {
        val lut = CubeFile.parse(cubeText(17) { r, g, b -> Triple(r, g, b) })
        check(lut.size == 17, "a 17 cube read as ${lut.size}")
        for ((r, g, b) in listOf(
            Triple(0f, 0f, 0f), Triple(1f, 1f, 1f), Triple(0.5f, 0.5f, 0.5f),
            Triple(0.2f, 0.7f, 0.9f), Triple(0.03f, 0.61f, 0.24f)
        )) {
            val out = lut.sample(r, g, b)
            check(near(out[0], r) && near(out[1], g) && near(out[2], b),
                "identity moved ($r, $g, $b) to (${out[0]}, ${out[1]}, ${out[2]})")
        }
        check(Lut3D.identity(17) == lut, "a parsed identity differs from the built one")
    }

    // --- A cube that does something does it everywhere. ---------------------
    run {
        // Swap red and blue: an unmistakable change, and one whose answer is
        // known at every colour rather than only at the corners.
        val lut = CubeFile.parse(cubeText(9) { r, g, b -> Triple(b, g, r) })
        for ((r, g, b) in listOf(Triple(1f, 0f, 0f), Triple(0.25f, 0.5f, 0.75f), Triple(0f, 1f, 1f))) {
            val out = lut.sample(r, g, b)
            check(near(out[0], b) && near(out[1], g) && near(out[2], r),
                "the swap moved ($r, $g, $b) to (${out[0]}, ${out[1]}, ${out[2]})")
        }
    }

    // --- Outside 0..1 is clamped, not wrapped. ------------------------------
    run {
        val lut = CubeFile.parse(cubeText(5) { r, g, b -> Triple(r, g, b) })
        val low = lut.sample(-1f, -1f, -1f)
        val high = lut.sample(2f, 2f, 2f)
        check(low.all { near(it, 0f) }, "below black read ${low.toList()}")
        check(high.all { near(it, 1f) }, "above white read ${high.toList()}")
    }

    // --- The atlas is the cube, rearranged and nothing else. ----------------
    run {
        val lut = CubeFile.parse(cubeText(8) { r, g, b -> Triple(b, r, g) })
        val atlas = lut.atlas()
        check(atlas.size == lut.data.size, "the atlas is ${atlas.size} where the cube is ${lut.data.size}")
        // Every entry of the cube is somewhere in the atlas, at the place the
        // shader will look for it: tile z across, x within it, y down.
        val n = lut.size
        var wrong = 0
        for (z in 0 until n) for (y in 0 until n) for (x in 0 until n) {
            val src = ((z * n + y) * n + x) * 3
            val dst = (y * (n * n) + z * n + x) * 3
            for (c in 0 until 3) if (!near(atlas[dst + c], lut.data[src + c], 1e-6f)) wrong++
        }
        check(wrong == 0, "$wrong atlas entries are not where the shader will look")
    }

    // --- A 1D table is three curves, grown into a cube. ---------------------
    run {
        val text = buildString {
            appendLine("LUT_1D_SIZE 4")
            appendLine("0 0 0")
            appendLine("0.1 0.2 0.3")
            appendLine("0.6 0.7 0.8")
            appendLine("1 1 1")
        }
        val lut = CubeFile.parse(text)
        check(lut.size == 4, "a 1D table of 4 grew to ${lut.size}")
        // Each channel follows its own curve and nothing else's.
        val mid = lut.sample(1f / 3f, 1f / 3f, 1f / 3f)
        check(near(mid[0], 0.1f) && near(mid[1], 0.2f) && near(mid[2], 0.3f), "the 1D table read ${mid.toList()} at a third")
        // Red at full and green at nothing: a 1D table never mixes channels.
        val corner = lut.sample(1f, 0f, 0f)
        check(near(corner[0], 1f) && near(corner[1], 0f) && near(corner[2], 0f), "the 1D table mixed channels: ${corner.toList()}")
    }

    // --- A long 1D table is a real file, and it used to take the app down. --
    // A 1D table is grown into a cube, so its length is cubed before anything
    // is allocated: a camera-matching LUT of 1024 entries asked for 1024 cubed
    // times three floats, which overflows Int to a negative and threw
    // NegativeArraySizeException straight out of the import. 256 and 512 died
    // the same way on an OutOfMemoryError. None of this was covered - the only
    // 1D table here was four entries long.
    for (long in intArrayOf(65, 128, 256, 512, 1024, 4096)) {
        val text = buildString {
            appendLine("LUT_1D_SIZE $long")
            // A straight ramp, so the cube it grows into has to be the identity.
            for (i in 0 until long) {
                val v = i.toFloat() / (long - 1)
                appendLine("$v $v $v")
            }
        }
        val lut = runCatching { CubeFile.parse(text) }
        check(lut.isSuccess, "a 1D table of $long threw ${lut.exceptionOrNull()}")
        lut.getOrNull()?.let { made ->
            check(made.size in 2..64, "a 1D table of $long grew to a cube of ${made.size}")
            listOf(0f, 0.25f, 0.5f, 1f).forEach { v ->
                val out = made.sample(v, v, v)
                check(out.all { near(it, v, 0.02f) }, "a straight 1D ramp of $long is not the identity at $v: ${out.toList()}")
            }
        }
    }
    run {
        val oneEntry = runCatching { CubeFile.parse("LUT_1D_SIZE 1\n0.5 0.5 0.5\n") }
        check(oneEntry.isFailure, "a 1D table of one entry was accepted")
    }

    // --- A domain other than 0..1 is brought back to it. --------------------
    run {
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0.0")
            appendLine("DOMAIN_MAX 2.0")
            for (z in 0..1) for (y in 0..1) for (x in 0..1) appendLine("${x * 2} ${y * 2} ${z * 2}")
        }
        val lut = CubeFile.parse(text)
        val white = lut.sample(1f, 1f, 1f)
        check(white.all { near(it, 1f) }, "a 0..2 domain did not come back to 0..1: ${white.toList()}")
    }

    // --- Comments, blank lines, commas and odd case. ------------------------
    run {
        val text = "# header\n\nlut_3d_size 2\n\n" + buildString {
            for (z in 0..1) for (y in 0..1) for (x in 0..1) appendLine("$x, $y, $z   # a note")
        }
        val lut = CubeFile.parse(text)
        check(lut.size == 2, "a lower-case, comma-separated, commented file read as ${lut.size}")
        check(near(lut.sample(1f, 0f, 0f)[0], 1f), "it read the wrong numbers")
    }

    // --- What a bad file says. ----------------------------------------------
    run {
        fun problem(text: String): String? = try { CubeFile.parse(text); null } catch (e: CubeFile.Problem) { e.message }
        check(problem("hello\nworld") != null, "a file with no size line was accepted")
        check(problem("LUT_3D_SIZE 4\n0 0 0\n")?.contains("holds") == true, "a short file did not say how short")
        check(problem("LUT_3D_SIZE 200\n")?.contains("200") == true, "an oversized cube did not say its size")
        // The messages are for a person, so they are sentences rather than codes.
        check(problem("hello")!!.endsWith("."), "the message is not a sentence: ${problem("hello")}")
    }

    println("lut: .cube 3D and 1D, atlas laid out for ES2")
    if (problems.isEmpty()) println("PASS - a cube reads, samples and flattens the way the shader expects")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
