import java.io.File
import kotlin.system.exitProcess

/*
 * Each shader and the Kotlin that drives it agree about uniforms.
 *
 * A port of tools/check_shaders.py, which has only ever run in the sandbox -
 * there is no Python on the desktop, so the one machine here that compiles
 * anything had no way to run it, and a uniform added on one side has gone
 * unchecked on every desktop sitting since. It is a suite now, so both
 * runners have it.
 *
 * This is the one shader mistake that cannot be seen by reading either file
 * alone, and it fails hard: Media3's GlProgram collects the *active* uniforms
 * when the program links, and setFloatsUniform on a name it did not find
 * throws. A uniform the GLSL never declares - a rename on one side, a typo, a
 * parameter added to the Kotlin and forgotten in the shader - is a crash on
 * the first frame, or a preview that is simply black with the exception
 * swallowed by the runCatching around setVideoEffects.
 *
 * The other direction is quieter and worse: a uniform declared in the shader
 * that nothing ever sets reads as zero, so the effect is subtly wrong rather
 * than missing, and it looks like a tuning problem for as long as you are
 * willing to believe it.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }

/** How many floats each GLSL type wants. Samplers are set by their own call. */
private val ARITY = mapOf("float" to 1, "vec2" to 2, "vec3" to 3, "vec4" to 4, "mat3" to 9, "mat4" to 16)

private val UNIFORM = Regex("""(?m)^\s*uniform\s+(\w+)\s+(\w+)\s*;""")
private val ATTRIBUTE = Regex("""(?m)^\s*attribute\s+(\w+)\s+(\w+)\s*;""")
// The name may be interpolated: the eight hue bands are set in a loop as
// "uHsl$i". A literal-only pattern did not see that setter at all and then
// reported all eight uniforms as never set - eight false alarms out of eight
// findings, which is the fastest way to teach anyone to ignore a checker. A
// name with a $ in it is read as the prefix before it.
private val SET_FLOATS = Regex("""setFloatsUniform\(\s*"([\w$]+)"\s*,\s*([^\n]*)""")
private val SET_FLOAT = Regex("""setFloatUniform\(\s*"([\w$]+)"""")
// Integers count too. Only the float setters were recognised once, so a uniform
// set with setIntUniform read as never set at all.
private val SET_INT = Regex("""setIntUniform\(\s*"([\w$]+)"""")
private val SET_SAMPLER = Regex("""setSamplerTexIdUniform\(\s*"([\w$]+)"""")
private val SET_ATTRIBUTE = Regex("""setBufferAttribute\(\s*"(\w+)"""")
private val FRAGMENT_PATH = Regex("""FRAGMENT_SHADER_PATH\s*=\s*"([^"]+)"""")
private val VERTEX_PATH = Regex("""VERTEX_SHADER_PATH\s*=\s*"([^"]+)"""")

private fun stripComments(text: String): String =
    text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

/** How many floats a floatArrayOf(...) call passes, when it is spelled out; null when it is not. */
private fun literalArity(argument: String): Int? {
    val m = Regex("""^\s*floatArrayOf\(([^)]*)\)""").find(argument) ?: return null
    val inner = m.groupValues[1].trim()
    if (inner.isEmpty()) return 0
    // Commas inside a nested call would miscount, so only a flat list is trusted.
    if ("(" in inner) return null
    return inner.split(",").size
}

fun main() {
    val assets = File("app/src/main/assets")
    val source = File("app/src/main/java")
    if (!assets.isDirectory || !source.isDirectory) {
        println("FAIL - the assets or source tree is not where this check looks"); exitProcess(1)
    }

    val shaders = assets.listFiles { f -> f.isFile && f.name.endsWith(".glsl") }?.sortedBy { it.name }.orEmpty()
    if (shaders.isEmpty()) { println("no shaders found"); return }

    val bodies = source.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .associate { it.name to it.readText() }

    val usedBy = HashMap<String, MutableList<String>>()
    bodies.forEach { (name, body) ->
        (FRAGMENT_PATH.findAll(body) + VERTEX_PATH.findAll(body)).forEach { m ->
            usedBy.getOrPut(m.groupValues[1]) { mutableListOf() } += name
        }
    }

    shaders.forEach { shader ->
        val text = stripComments(shader.readText())
        val open = text.count { it == '{' }
        val close = text.count { it == '}' }
        if (open != close) flag("${shader.name}: $open open braces, $close close")
        if (!text.contains("void main(")) flag("${shader.name}: no main()")
        if (!text.contains("precision") && !shader.name.contains("vertex")) {
            flag("${shader.name}: fragment shader with no precision qualifier")
        }

        val declared = UNIFORM.findAll(text).associate { it.groupValues[2] to it.groupValues[1] }
        val attributes = ATTRIBUTE.findAll(text).map { it.groupValues[2] }.toSet()

        val drivers = usedBy[shader.name].orEmpty()
        if (drivers.isEmpty()) { flag("${shader.name}: nothing references it"); return@forEach }

        drivers.forEach { driver ->
            val body = bodies.getValue(driver)
            // A vertex shader is shared, so only judge it on what it declares.
            if (shader.name.contains("vertex")) {
                val setAttrs = SET_ATTRIBUTE.findAll(body).map { it.groupValues[1] }.toSet()
                attributes.filterNot { it in setAttrs }.forEach { flag("$driver: never sets attribute '$it'") }
                return@forEach
            }

            val set = HashSet<String>()
            // A "uHsl$i" counts for every uniform called uHsl-something.
            val prefixes = HashSet<String>()
            fun note(name: String) {
                if ('$' in name) prefixes += name.substringBefore('$') else set += name
            }
            listOf(SET_FLOAT, SET_INT, SET_SAMPLER).forEach { r -> r.findAll(body).forEach { note(it.groupValues[1]) } }
            SET_FLOATS.findAll(body).forEach { m ->
                val name = m.groupValues[1]
                note(name)
                val kind = declared[name] ?: return@forEach
                val wanted = ARITY[kind]
                val given = literalArity(m.groupValues[2])
                if (wanted != null && given != null && wanted != given) {
                    flag("$driver: '$name' is $kind in ${shader.name} but is handed $given float(s)")
                }
            }
            // A prefix covers only a name it indexes: "uHsl$i" answers for
            // uHsl0..uHsl7 and not for a uHslSomething nothing sets, which is
            // the hole a bare startsWith would leave.
            fun covered(name: String) = name in set || prefixes.any {
                name.length > it.length && name.startsWith(it) && name.drop(it.length).all { c -> c.isDigit() }
            }
            set.sorted().filterNot { it in declared }.forEach {
                flag("$driver: sets '$it', which ${shader.name} does not declare - GlProgram throws on this")
            }
            declared.keys.sorted().filterNot { covered(it) }.forEach {
                flag("${shader.name}: declares '$it', which $driver never sets - it reads as zero")
            }
        }
    }

    println("shaders: ${shaders.size} checked against the Kotlin that drives them")
    if (problems.isEmpty()) println("PASS - every uniform a shader declares is set, and every uniform set is declared")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
