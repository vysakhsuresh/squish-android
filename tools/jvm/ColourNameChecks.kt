import com.squish.app.ui.components.ColourName
import kotlin.system.exitProcess

/*
 * A colour said out loud, executed.
 *
 * Every swatch in the app was a bare circle whose only content was its own
 * fill, so with a screen reader on there was no non-visual route to setting a
 * colour anywhere - nine identical nameless targets on the Style tab, and the
 * "More..." pad behind them two raw pointer inputs with no node at all. The
 * eyedropper beside them has always carried "Pick a colour from the picture",
 * which is how you can tell this was a gap rather than the house style.
 *
 * The names have to be *distinct across a palette*, which is the thing that
 * makes them useful and the thing a table beside each palette would not
 * guarantee. So that is what this asserts, over every palette in the app.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/*
 * The app's three palettes, copied here. The copies are held honest by
 * tools/jvm/ControlChecks.kt, which asserts each palette's length against the
 * numbers below - so adding a tenth colour fails that check and points here,
 * rather than quietly leaving the new colour uncovered.
 */

/** TextSheet's CAPTION_COLOURS: text, outline, shadow, bubble and a shape. */
private val CAPTION = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFFFD166.toInt(), 0xFFFF4FD8.toInt(),
    0xFF5CE1E6.toInt(), 0xFF7CFC8A.toInt(), 0xFFFF6B6B.toInt(), 0xFF4A7BFF.toInt(),
    0xFFFF7A45.toInt()
)

/** FrameSheet's CANVAS_COLOURS: what fills a ratio's canvas round the picture. */
private val CANVAS = listOf(
    0xFF000000.toInt(), 0xFF101828.toInt(), 0xFFFFFFFF.toInt(), 0xFF2563EB.toInt(),
    0xFF00B140.toInt(), 0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF7C3AED.toInt(),
    0xFFEF4444.toInt()
)

/** BackgroundPanel's BACKDROPS: what a cut-out's background is painted with. */
private val BACKDROPS = listOf(
    0xFF101828.toInt(), 0xFFFFFFFF.toInt(), 0xFF00B140.toInt(), 0xFF2563EB.toInt(),
    0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF7C3AED.toInt()
)

private val PALETTES = listOf("caption" to CAPTION, "canvas" to CANVAS, "backdrops" to BACKDROPS)

fun main() {
    // ---- The greys, which have no hue to say. ------------------------------
    check(ColourName.of(0xFFFFFFFF.toInt()) == "white", "white reads \"${ColourName.of(0xFFFFFFFF.toInt())}\"")
    check(ColourName.of(0xFF000000.toInt()) == "black", "black reads \"${ColourName.of(0xFF000000.toInt())}\"")
    // The caption palette's near-black: black is what anyone would call it.
    check(ColourName.of(0xFF111111.toInt()) == "black", "#111 reads \"${ColourName.of(0xFF111111.toInt())}\"")
    check(ColourName.of(0xFF333333.toInt()) == "dark grey", "#333 reads \"${ColourName.of(0xFF333333.toInt())}\"")
    check(ColourName.of(0xFF808080.toInt()) == "grey", "mid grey reads \"${ColourName.of(0xFF808080.toInt())}\"")
    check(ColourName.of(0xFFDDDDDD.toInt()) == "light grey", "#DDD reads \"${ColourName.of(0xFFDDDDDD.toInt())}\"")

    // ---- The hues, each named as somebody would say it. --------------------
    listOf(
        0xFFFF0000.toInt() to "red",
        0xFFFF8000.toInt() to "orange",
        0xFFFFFF00.toInt() to "yellow",
        0xFF00FF00.toInt() to "green",
        0xFF00FFFF.toInt() to "cyan",
        0xFF0000FF.toInt() to "blue",
        0xFFFF00FF.toInt() to "magenta"
    ).forEach { (argb, want) ->
        check(ColourName.of(argb).endsWith(want), "${hex(argb)} reads \"${ColourName.of(argb)}\", wanted …$want")
    }

    // ---- A palette's names are all different. ------------------------------
    //
    // The point of naming them at all: nine swatches that all announce "blue"
    // are no better than nine that announce nothing.
    PALETTES.forEach { (which, palette) ->
        val names = palette.map { ColourName.of(it) }
        check(
            names.distinct().size == names.size,
            "two $which colours share a name: ${names.groupBy { it }.filterValues { it.size > 1 }.keys}"
        )
        // And each is something a person would say, not a hex code.
        check(names.none { it.contains('#') || it.isBlank() }, "a $which colour has no name: $names")
    }

    // ---- Every colour has a name, and it never throws. ---------------------
    run {
        var n = 0
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val name = runCatching { ColourName.of(argb) }.getOrNull()
            if (name.isNullOrBlank()) { problems += "${hex(argb)} has no name"; return@run }
            n++
        }
        check(n == 16 * 16 * 16, "only $n colours were named")
    }

    // ---- The alpha is ignored: a swatch is opaque. -------------------------
    check(
        ColourName.of(0x00FF0000) == ColourName.of(0xFFFF0000.toInt()),
        "the alpha changed the name: \"${ColourName.of(0x00FF0000)}\" vs \"${ColourName.of(0xFFFF0000.toInt())}\""
    )

    // ---- Near-grey is grey, not a wildly swinging hue. ---------------------
    //
    // A hue read off two almost-equal channels is noise, and "lime" for what
    // anyone would call grey is worse than "grey".
    listOf(0xFF808182.toInt(), 0xFF828180.toInt(), 0xFF818281.toInt()).forEach { argb ->
        check(ColourName.of(argb) == "grey", "${hex(argb)} reads \"${ColourName.of(argb)}\" rather than grey")
    }

    if (problems.isEmpty()) {
        println("ColourNameChecks: every colour has a name, and a palette's names are all different")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
