import android.net.Uri
import com.squish.app.media.SmartRender
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import kotlin.system.exitProcess

/*
 * EDGE_PLAN C2: which shots an export may copy rather than encode. Every field
 * of Clip that is not identity or window, set off its default, must make the
 * shot uncopyable - walked by reflection over the constructor, so a field added
 * later is tested without this file knowing its name.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun shot() = Clip(
    id = "a", kind = ClipKind.Video, uri = Uri.parse("content://a"), label = "a",
    sourceInMs = 1_000, sourceOutMs = 6_000, timelineStartMs = 0, sourceDurationMs = 60_000
)

fun main() {
    val plain = shot()
    check(SmartRender.plainShot(plain), "an untouched shot is not copyable")
    check(SmartRender.plainShot(plain.copy(beats = listOf(0L, 500L))), "beats made a shot uncopyable")
    check(SmartRender.plainShot(plain.copy(label = "renamed", sourceInMs = 2_000)), "a rename or a trim made a shot uncopyable")

    // A handful by name, for the message.
    check(!SmartRender.plainShot(plain.copy(volume = 0.5f)), "a quieter shot read as copyable")
    check(!SmartRender.plainShot(plain.copy(speedRamp = SpeedRamp.flat(2f))), "a 2x shot read as copyable")
    check(!SmartRender.plainShot(plain.copy(lookId = "noir")), "a graded shot read as copyable")
    check(!SmartRender.plainShot(plain.copy(transitionIn = Transition(TransitionType.CrossFade))), "a shot with a transition read as copyable")
    check(!SmartRender.plainShot(plain.copy(layer = 1)), "an overlay read as copyable")
    check(!SmartRender.plainShot(plain.copy(kind = ClipKind.Audio)), "a sound read as copyable")

    // Every other field, off its default: walked through copy() by reflection.
    val identity = setOf("id", "kind", "uri", "label", "sourceInMs", "sourceOutMs", "timelineStartMs", "sourceDurationMs", "beats")
    val copyFn = Clip::class.members.first { it.name == "copy" } as kotlin.reflect.KFunction<*>
    var tried = 0
    for (p in copyFn.parameters.drop(1)) {
        val name = p.name ?: continue
        if (name in identity) continue
        val current = Clip::class.members.first { it.name == name }.call(plain)
        val other: Any? = when (current) {
            is Boolean -> !current
            is Float -> current + 0.37f
            is Double -> current + 0.37
            is Int -> current + 1
            is Long -> current + 17L
            is String -> current + "x"
            null -> continue // an optional thing with no default value to nudge (a look id, a crop): named above where it matters
            is List<*> -> if (current.isEmpty()) continue else emptyList<Any>()
            else -> continue
        }
        val changed = runCatching { copyFn.callBy(mapOf(copyFn.parameters[0] to plain, p to other)) as Clip }.getOrNull() ?: continue
        tried++
        check(!SmartRender.plainShot(changed), "Clip.$name changed and the shot still read as copyable")
    }
    check(tried >= 20, "only $tried fields nudged - this check has rotted")

    // Only one shot, from the edit's start.
    check(SmartRender.copyableShot(listOf(plain)) == plain, "a lone plain shot was not chosen")
    check(SmartRender.copyableShot(listOf(plain, plain.copy(id = "b", timelineStartMs = 5_000))) == null, "two shots were copied")
    check(SmartRender.copyableShot(listOf(plain.copy(timelineStartMs = 2_000))) == null, "a shot after a gap was copied")
    check(SmartRender.copyableShot(emptyList()) == null, "nothing was copied")

    if (problems.isEmpty()) println("SmartRenderChecks: all checks passed ($tried fields nudged)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
