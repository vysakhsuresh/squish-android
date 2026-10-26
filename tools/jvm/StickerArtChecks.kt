import com.squish.app.editor.ACCENT
import com.squish.app.editor.StickerArt
import com.squish.app.editor.StickerDesign
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F4: the designed sticker pack, as CaptionRenderer will be handed it.
 * Every design inside its own box at its own shape and at a squeezed one (the
 * renderer pads for the stroke, not for points that wander off); something to
 * draw in every one; the sticker's colour reaching every one, or recolouring
 * would do nothing; words where a design says it has them and nowhere else,
 * inside the box; each name its own; every shelf stocked.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    for (d in StickerDesign.offered) for (squeeze in listOf(1f, 0.6f, 1.6f)) {
        val h = 200f
        val w = h * d.aspect * squeeze
        val parts = StickerArt.parts(d, w, h)
        check(parts.isNotEmpty(), "$d draws nothing")
        check(parts.any { it.colour == ACCENT || it.colour == StickerArt.ACCENT_HALF }, "$d takes none of the sticker's colour")
        for (part in parts) {
            check(part.polys.isNotEmpty() || (part.icon != null && part.rect != null), "$d has a part that is neither a shape nor an icon")
            for (poly in part.polys) for (pt in poly.points) {
                check(pt.x >= -0.5f && pt.x <= w + 0.5f && pt.y >= -0.5f && pt.y <= h + 0.5f, "$d at ${w}x$h has a point outside its box: (${pt.x}, ${pt.y})")
                check(pt.x.isFinite() && pt.y.isFinite(), "$d has a point that is not a number")
            }
            part.rect?.let { r -> check(r.left >= 0f && r.top >= 0f && r.right <= w + 0.5f && r.bottom <= h + 0.5f && r.width > 0f && r.height > 0f, "$d's icon box is outside it: $r") }
            check(part.strokeShare in 0f..0.4f, "$d strokes at ${part.strokeShare}")
        }
        val words = StickerArt.words(d, w, h)
        check((words != null) == d.hasWords, "$d says it has words (${d.hasWords}) and has a place for them (${words != null})")
        words?.let { r -> check(r.rect.left >= 0f && r.rect.top >= 0f && r.rect.right <= w + 0.5f && r.rect.bottom <= h + 0.5f && r.rect.width > 0f && r.rect.height > 0f, "$d's words fall outside it: ${r.rect}") }
    }
    // ---- The animated pack: inside its box at every moment, seamless round the loop, and moving ----
    val animated = StickerDesign.offered.filter { it.animated }
    check(animated.size >= 10, "only ${animated.size} animated designs")
    for (d in animated) {
        val h = 200f
        val w = h * d.aspect
        fun sig(atMs: Long) = StickerArt.parts(d, w, h, atMs).joinToString("|") { part ->
            part.polys.joinToString(";") { poly -> poly.points.joinToString(",") { "%.2f/%.2f".format(java.util.Locale.ROOT, it.x, it.y) } } +
                "#${part.rect}#${"%.3f".format(java.util.Locale.ROOT, part.alpha)}"
        }
        for (step in 0 until 24) {
            val at = d.loopMs * step / 24
            for (part in StickerArt.parts(d, w, h, at)) {
                for (poly in part.polys) for (pt in poly.points) {
                    check(pt.x >= -0.5f && pt.x <= w + 0.5f && pt.y >= -0.5f && pt.y <= h + 0.5f, "$d at ${at} ms has a point outside its box: (${pt.x}, ${pt.y})")
                }
                part.rect?.let { r -> check(r.left >= -0.5f && r.top >= -0.5f && r.right <= w + 0.5f && r.bottom <= h + 0.5f, "$d at $at ms has its icon outside its box: $r") }
                check(part.alpha in 0f..1f, "$d at $at ms fades to ${part.alpha}")
            }
        }
        check(sig(0) == sig(d.loopMs) && sig(d.loopMs / 4) == sig(d.loopMs + d.loopMs / 4), "$d is not the same a loop later - it jumps where it goes round")
        val frames = (0 until 12).map { sig(d.loopMs * it / 12) }.toSet()
        check(frames.size >= 6, "$d hardly moves: ${frames.size} different pictures in 12")
        // The caches' frame: thirty a second round the loop, the same a loop later.
        check(StickerArt.frameOf(d, 0) == 0 && StickerArt.frameOf(d, d.loopMs + 100) == StickerArt.frameOf(d, 100), "$d's frame number does not go round with it")
        check((0 until d.loopMs.toInt() step 33).map { StickerArt.frameOf(d, it.toLong()) }.toSet().size >= d.loopMs / 40, "$d's frame number hardly changes over its loop - the caches would hold one picture")
    }
    check(StickerArt.frameOf(StickerDesign.PriceTag, 12_345) == 0, "a still design changes its frame")

    check(StickerDesign.offered.size >= 24, "only ${StickerDesign.offered.size} designs")
    check(StickerDesign.offered.map { it.label }.toSet().size == StickerDesign.offered.size, "two designs share a name")
    for (shelf in StickerDesign.Shelf.entries) check(StickerDesign.offered.count { it.shelf == shelf } >= 3, "${shelf.label} has fewer than three")
    check(StickerDesign.named("PriceTag") == StickerDesign.PriceTag && StickerDesign.named("nonsense") == StickerDesign.None, "a design is not read back by its name")

    if (problems.isEmpty()) println("StickerArtChecks: all checks passed (${StickerDesign.offered.size} designs)")
    else { problems.take(30).forEach { println("FAIL: $it") }; println("(${problems.size} in all)"); exitProcess(1) }
}
