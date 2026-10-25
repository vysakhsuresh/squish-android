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

        // A uniform declared and set and never *read* is the quietest of the
        // three ways this can go wrong. The other two fail loudly - a setter
        // for a name the GLSL does not declare throws when the program links,
        // and a uniform nothing sets reads as zero - but one the body never
        // mentions simply does nothing, so the knob on the sheet moves and the
        // picture does not, which looks like a tuning problem for as long as
        // anyone is willing to believe it. (A misspelling in the body is a
        // different thing and louder: GLSL refuses an undeclared identifier,
        // which fails on the player asynchronously and plays the surface
        // plain.)
        val body = text.substringAfter("void main(", "")
        declared.keys.sorted().forEach { name ->
            // The whole file past the declarations, so a helper function above
            // main() counts as a use.
            val usedAnywhere = Regex("""\b${Regex.escape(name)}\b""").findAll(text).count()
            if (usedAnywhere <= 1 && name !in body) {
                flag("${shader.name}: declares '$name' and never reads it - the knob moves and the picture does not")
            }
        }

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

    // --- The one blur, written four times. ------------------------------------
    //
    // A softening is `uBlur` taps a fraction of the frame apart, and four files
    // hold a copy of it: the effects pass, the AGSL copy of that pass that
    // Android 13+ draws over the canvas, the transition pass (a Defocus join on
    // a base shot) and the premultiply pass (the same join on an overlay). They
    // are four because they live in different places, not because they do
    // different things - and a placed Blur, a defocus on a shot and a defocus on
    // an overlay must all look the same, in the preview and in the file alike.
    //
    // So each must be the same ring: a 3x3 of taps uBlur apart, averaged. The
    // moment one of them is widened or re-shaped on its own, this says so.
    run {
        val ring = listOf(
            "app/src/main/assets/squish_fx_es2.glsl",
            "app/src/main/assets/squish_transition_es2.glsl",
            "app/src/main/assets/squish_premultiply_es2.glsl",
            "app/src/main/java/com/squish/app/editor/CanvasFx.kt"
        )
        ring.forEach { path ->
            val text = File(path).takeIf { it.isFile }?.readText()
            if (text == null) { flag("$path is not where the blur check looks"); return@forEach }
            if ("uBlur" !in text) { flag("$path no longer has a blur at all"); return@forEach }
            val loops = Regex("""for \(int i = -1; i <= 1; i\+\+\)""").findAll(text).count()
            val inner = Regex("""for \(int j = -1; j <= 1; j\+\+\)""").findAll(text).count()
            val mean = Regex("""/ 9\.0""").findAll(text).count()
            if (loops != 1 || inner != 1) flag("$path: the blur is not one 3x3 ring ($loops by $inner loops)")
            if (mean != 1) flag("$path: the blur's taps are not averaged over nine ($mean divisions)")
            // Exactly uBlur apart, with nothing applied after it: a copy that
            // doubles its own reach is the way these four quietly stop agreeing.
            val spacing = Regex("""\(float\(i\), float\(j\)\)\s*\*\s*uBlur\s*[,)]""").findAll(text).count()
            if (spacing != 1) flag("$path: the taps are not spaced exactly uBlur apart ($spacing match(es)) - this copy has drifted from the other three")
        }
    }

    // --- The library's second set, written twice. -----------------------------
    //
    // EDGE_PLAN F2's forty effects are one block of stages that the export's
    // pass (GLSL) and the preview's canvas pass (AGSL, Android 13+) both hold.
    // The block is the same text in both but for the type names, so a stage
    // retuned in one and not the other - a preview that disagrees with its
    // file - is a difference this can see. And the AGSL is a string in a
    // Kotlin file that the asset loop above never reads, so its uniforms are
    // held to its setters here: RuntimeShader throws on a name it lacks, and a
    // uniform nothing sets reads as zero.
    run {
        val begin = "// ---- The library's second set of stages (EDGE_PLAN F2) ----"
        val end = "// ---- end of the second set ----"
        fun block(path: String): String? {
            val text = File(path).takeIf { it.isFile }?.readText() ?: return null
            val a = text.indexOf(begin); val b = text.indexOf(end)
            if (a < 0 || b < a || text.indexOf(begin, a + 1) >= 0) return null
            return text.substring(a, b).lines().joinToString("\n") { it.trim() }.trim()
        }
        val glslPath = "app/src/main/assets/squish_fx_es2.glsl"
        val agslPath = "app/src/main/java/com/squish/app/editor/CanvasFx.kt"
        val glsl = block(glslPath)
        val agsl = block(agslPath)
        if (glsl == null || agsl == null) {
            flag("the second set of effect stages is missing, or there twice, in ${if (glsl == null) glslPath else agslPath}")
        } else {
            val spelled = glsl.replace(Regex("""\bvec([234])\b"""), "float$1")
            if (spelled != agsl) {
                val g = spelled.lines(); val s = agsl.lines()
                val at = g.indices.firstOrNull { it >= s.size || g[it] != s[it] } ?: g.size
                flag("the effect stages in $glslPath and $agslPath part at line ${at + 1} of the block: '${g.getOrNull(at)}' against '${s.getOrNull(at)}'")
            }
            listOf("warp(uv)", "resample(c, uv)", "lightAndTone(c, ").forEach { call ->
                listOf(glslPath, agslPath).forEach { path ->
                    val main = File(path).readText().substringAfter("main(", "")
                    if (call !in main.substringAfter(end, main)) flag("$path never calls $call - the stage is written and not run")
                }
            }
        }
        val canvas = File(agslPath).readText()
        val shaderText = canvas.substringAfter("AGSL = \"\"\"", "").substringBefore("\"\"\"")
        val agslUniforms = Regex("""(?m)^\s*uniform\s+(\w+)\s+(\w+)\s*;""").findAll(shaderText)
            .filter { it.groupValues[1] != "shader" }.map { it.groupValues[2] }.toSet()
        val agslSet = SET_FLOAT.findAll(canvas).map { it.groupValues[1] }.toSet()
        (agslUniforms - agslSet).sorted().forEach { flag("CanvasFx.kt: the AGSL declares '$it' and nothing sets it - it reads as zero") }
        (agslSet - agslUniforms).sorted().forEach { flag("CanvasFx.kt: sets '$it', which its AGSL does not declare - RuntimeShader throws") }
    }

    println("shaders: ${shaders.size} checked against the Kotlin that drives them")
    if (problems.isEmpty()) println("PASS - every uniform a shader declares is set, and every uniform set is declared")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
