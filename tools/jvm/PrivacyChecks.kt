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

    println("privacy: one file opens connections, and it can only ask")
    if (problems.isEmpty()) println("PASS - nothing outside online/Online.kt can reach the network, and nothing it sends has a body")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
