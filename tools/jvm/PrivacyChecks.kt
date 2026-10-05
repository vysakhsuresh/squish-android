import java.io.File
import kotlin.system.exitProcess

/*
 * The promise on the Settings screen, held up against the code.
 *
 * Squish tells people in as many words that nothing it holds is uploaded: not
 * a video, not a photo, not a project. That is the most expensive sentence in
 * the app - it is the one a person decides to trust us on - and until now
 * nothing but a convention kept it true.
 *
 * Two structural facts make it true, and this is what asserts them:
 *
 *   1. One file opens connections. Everything online goes through
 *      `online/Online.kt`, which refuses while the Settings switch is off.
 *      A connection opened anywhere else would be outside that switch.
 *   2. That file can only ask, never tell. Its one `open` is a GET with no
 *      body: no `doOutput`, no `requestMethod`, no `outputStream` on the
 *      connection. A request with no body cannot carry a file.
 *
 * Neither is clever. Both are the kind of thing that quietly stops being true
 * the first time someone adds a feature in a hurry.
 */
fun main() {
    val problems = mutableListOf<String>()
    val src = File("app/src/main/java/com/squish/app")
    if (!src.isDirectory) { println("FAIL - the source tree is not where this check looks"); exitProcess(1) }

    val files = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.path.replace('\\', '/') to it.readText() }.toList()
    if (files.size < 100) problems += "only ${files.size} Kotlin files found - has this check's path rotted?"

    // ---- The two sentences that say what leaves the phone -------------------
    //
    // One is the card in Settings; one is the dialog the person taps Continue
    // on, which is the one actually agreed to. They have to name the same
    // things. The card was changed to name a caption's words when Translate
    // captions was added - translating sends the line itself - and the dialog
    // was not, so the sentence a person agreed to was untrue for the one tool
    // whose request is their own words.
    run {
        fun sentence(path: String, anchor: String, what: String) {
            val text = files.firstOrNull { it.first.endsWith(path) }?.second
            if (text == null) { problems += "$path is not where this check looks"; return }
            val at = text.indexOf(anchor)
            if (at < 0) { problems += "$what no longer has its sentence (looked for \"$anchor\")"; return }
            val window = text.substring(at, minOf(text.length, at + 600))
            listOf("search", "caption", "the name of", "never your videos, photos or projects").forEach {
                if (it !in window) problems += "$what does not say \"$it\""
            }
        }
        sentence("/online/Online.kt", "Squish will connect to fetch it.", "the consent dialog")
        sentence("/settings/SettingsScreen.kt", "Only your search", "the Settings card")
    }

    // ---- One door ----------------------------------------------------------
    val theDoor = "app/src/main/java/com/squish/app/online/Online.kt"
    val waysOut = listOf(
        "openConnection", "HttpURLConnection", "URLConnection",
        "java.net.Socket", "SocketFactory", "okhttp", "OkHttp", "retrofit", "Retrofit",
        "WebView", "DatagramSocket"
    )
    files.forEach { (path, text) ->
        if (path.endsWith("/online/Online.kt")) return@forEach
        waysOut.forEach { way ->
            if (text.contains(way)) {
                problems += "$path names \"$way\". Every request goes through online/Online.kt, which is " +
                    "what the Settings switch turns off; a connection opened anywhere else is outside it"
            }
        }
    }

    // ---- And it only asks --------------------------------------------------
    val door = File(theDoor)
    if (!door.isFile) problems += "$theDoor is gone - this check has rotted" else {
        val text = door.readText()
        listOf(
            "doOutput" to "a connection with an output stream can carry a body, and a body can carry a file",
            "requestMethod" to "the one method is GET, by not naming one; naming one is how a POST arrives",
            "connection.outputStream" to "writing to the connection is uploading",
            "setFixedLengthStreamingMode" to "a declared body length is a body",
            "setChunkedStreamingMode" to "a chunked body is a body"
        ).forEach { (needle, why) ->
            if (text.contains(needle)) problems += "Online.kt names \"$needle\": $why"
        }
        if (!text.contains("if (!isEnabled(context)) throw Disabled()")) {
            problems += "Online.open no longer refuses while the Settings switch is off"
        }
    }

    // ---- And the manifest asks for no more than it needs --------------------
    val manifest = File("app/src/main/AndroidManifest.xml")
    if (!manifest.isFile) problems += "the manifest is not where this check looks" else {
        val text = manifest.readText()
        val net = Regex("""android:name="android\.permission\.([A-Z_]*(?:INTERNET|NETWORK|WIFI)[A-Z_]*)"""")
            .findAll(text).map { it.groupValues[1] }.toSortedSet()
        (net - setOf("INTERNET")).forEach {
            problems += "the manifest asks for $it. INTERNET is the only networking permission the online " +
                "features need, and anything more is a promise that is harder to read"
        }
    }

    // ---- And nothing deletes what it did not make ---------------------------
    //
    // The other promise, on the delete dialog: "Your original videos are
    // untouched either way." Three places delete through the content resolver
    // and all three delete a row this app has just inserted, by its own URI.
    // A delete with a *selection* is the shape that takes somebody else's
    // file - a pattern over display names is how one goes wrong, and `_` is a
    // wildcard in SQL LIKE, so "squish\_%" matches more than it reads as.
    run {
        val mayDelete = setOf(
            "app/src/main/java/com/squish/app/media/GallerySaver.kt",
            "app/src/main/java/com/squish/app/media/gif/GifMaker.kt"
        )
        files.forEach { (path, text) ->
            Regex("""(?:contentResolver|resolver)\.delete\(([^)]*)\)""").findAll(text).forEach { m ->
                if (path !in mayDelete) {
                    problems += "$path deletes through the content resolver. Only the gallery saver and the " +
                        "GIF writer may, and only the row they have just inserted"
                }
                val args = m.groupValues[1].split(",").map { it.trim() }
                if (args.size >= 2 && args.drop(1).any { it != "null" }) {
                    problems += "$path deletes with a selection (${m.groupValues[1]}). A delete by URI takes the " +
                        "row this app made; a delete by query takes whatever matches, and it is the user's gallery"
                }
            }
        }
    }

    println("privacy: one file opens connections, it can only ask, and nothing deletes what it did not make")
    if (problems.isEmpty()) println("PASS - no connection outside online/Online.kt, no body on a request, no delete by query")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
