import com.squish.app.editor.SoundStickers
import com.squish.app.media.audio.MusicSynth
import kotlin.system.exitProcess

// Sound stickers against the synth's own effects. The one way this list can
// rot is an effect renamed and every sticker that named it landing silently,
// which no amount of reading the two files side by side would catch twice.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    check(SoundStickers.all.isNotEmpty(), "there are no sound stickers")

    SoundStickers.all.forEach { pair ->
        val effect = MusicSynth.effectById(pair.effectId)
        check(effect != null, "${pair.label} names \"${pair.effectId}\", which the synth does not have")
        check(effect == null || effect.seconds > 0f, "${pair.label}'s effect is ${effect?.seconds}s long")
        check(pair.emoji.isNotBlank(), "${pair.label} has no emoji")
        check(pair.label.isNotBlank(), "${pair.emoji} has no name")
    }

    check(
        SoundStickers.all.map { it.emoji }.distinct().size == SoundStickers.all.size,
        "two sound stickers share an emoji, so one of them can never be tapped"
    )
    check(
        SoundStickers.all.map { it.effectId }.distinct().size == SoundStickers.all.size,
        "two sound stickers share a sound"
    )

    // Looked up the way the panel looks one up.
    SoundStickers.all.forEach { pair ->
        check(SoundStickers.forEmoji(pair.emoji) === pair, "${pair.emoji} could not be found by its own emoji")
    }
    check(SoundStickers.forEmoji("🥦") == null, "a sticker with no sound was given one")
    check(SoundStickers.forEmoji("") == null, "no emoji at all found a sound")

    println("sound stickers: ${SoundStickers.all.size} pairs against ${MusicSynth.effects.size} effects")
    if (problems.isEmpty()) println("PASS - every sound sticker names a sound the synth can make")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
