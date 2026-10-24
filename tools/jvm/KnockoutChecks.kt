import com.squish.app.editor.TextFrame
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextMotion
import kotlin.system.exitProcess

/*
 * EDGE_PLAN E4: video seen through big letters. A knockout line is a card the
 * size of the frame in the line's colour with its letters cut out, drawn by
 * the one caption renderer in the preview and in the file. So it is placed at
 * the frame's middle and never moved or turned as a whole - the letters are
 * cut where the line stands, at its own turn - while a Zoom arrival still
 * grows the card (the frame stays covered), and a fade still fades it.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val plain = TextOverlayItem(id = "p", text = "SUMMER", startMs = 0, endMs = 4_000, colorArgb = -1, xFraction = 0.3f, yFraction = 0.7f, rotationDegrees = 12f)
    val cut = plain.copy(knockout = true)

    check(plain.drawnAt(1_000) == (0.3f to 0.7f), "a plain line is drawn at ${plain.drawnAt(1_000)}")
    check(cut.drawnAt(1_000) == (0.5f to 0.5f), "a knockout card is drawn at ${cut.drawnAt(1_000)}, not the frame's middle")
    check(cut.cutAt(1_000) == (0.3f to 0.7f), "the letters are cut at ${cut.cutAt(1_000)}, not where the line stands")
    check(cut.drawnTurn(TextFrame(tilt = 5f)) == 0f && plain.drawnTurn(TextFrame(tilt = 5f)) == 17f, "the card was turned, or the line was not")
    check(cut.cutTurn(TextFrame(tilt = 5f)) == 17f, "the letters are not cut at the line's turn")

    // Motion: the card never rises off the frame or shrinks inside it; a fade still fades it.
    val moving = TextFrame(alpha = 0.4f, scale = 0.6f, rise = 0.1f, tilt = 30f)
    val shown = cut.drawnFrame(moving)
    check(shown.rise == 0f && shown.scale >= 1f && shown.tilt == 0f, "the card moved off the frame: $shown")
    check(shown.alpha == 0.4f, "the card did not fade with the line")
    val zoom = cut.drawnFrame(TextFrame(scale = 2.2f))
    check(zoom.scale == 2.2f, "a Zoom arrival no longer grows the card")
    check(plain.drawnFrame(moving) == moving, "a plain line's motion was changed")

    // A knockout is the line's own, not its style: Apply to all does not cut every line out.
    check(cut.withStyle(plain.style).knockout, "applying a style took the knockout off")
    check(!plain.withStyle(cut.style).knockout, "a style carried the knockout to another line")

    if (problems.isEmpty()) println("KnockoutChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
