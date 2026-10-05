package com.squish.app.online

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether Squish may go online at all - off until the person says otherwise.
 *
 * The promise is that a video never leaves the phone, and that stays true:
 * nothing here uploads anything. What online features do is *fetch* - free
 * music to put under a video, fonts for its words - and only once the person
 * has turned them on, from Settings or from the question a tool that needs the
 * internet asks first. Every request goes through [get], which refuses while
 * the switch is off, so no screen can forget to ask.
 */
object Online {

    private val state = MutableStateFlow<Boolean?>(null)

    /** The switch, for a screen to follow. */
    fun enabledFlow(context: Context): StateFlow<Boolean?> {
        if (state.value == null) state.value = read(context)
        return state
    }

    fun isEnabled(context: Context): Boolean = state.value ?: read(context).also { state.value = it }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        state.value = on
    }

    private fun read(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Thrown for a request made while the switch is off: the caller asked first, or it has a bug. */
    class Disabled : IOException("Online features are off")

    /** A GET, as text. Off the main thread; refuses while the switch is off. */
    suspend fun get(context: Context, url: String): String = withContext(Dispatchers.IO) {
        open(context, url).run {
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    /** A GET into [target], through a partial file so a dropped connection leaves nothing half-written. */
    suspend fun download(context: Context, url: String, target: File): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        val connection = open(context, url)
        try {
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            connection.disconnect()
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("could not keep the download")
        }
        target
    }

    private fun open(context: Context, url: String): HttpURLConnection {
        if (!isEnabled(context)) throw Disabled()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return connection
    }

    /** See the top-level [com.squish.app.online.searchTerms], which is where it can be executed. */
    fun searchTerms(typed: String): String? = com.squish.app.online.searchTerms(typed)

    private const val PREFS = "settings"
    private const val KEY = "online_enabled"
    private const val TIMEOUT_MS = 15_000

    /**
     * Named, as a polite client is. Also what Google Fonts answers with TrueType
     * rather than WOFF2 for - the one format Android's Typeface reads from a file.
     */
    private const val USER_AGENT = "Squish/1.0 (Android video editor)"
}

/**
 * The question an internet tool asks before it goes online: what goes out,
 * what comes back, and that the video does not. Yes turns online features on
 * (Settings can turn them off again) and carries on with what was tapped.
 * With online features already on, it just carries on.
 */
class OnlineGate internal constructor(private val context: Context, private val ask: (String, () -> Unit) -> Unit) {
    /** Runs [go] once online features are on, asking first when they are not. [what] names the tool. */
    fun request(what: String, go: () -> Unit) {
        if (Online.isEnabled(context)) go() else ask(what, go)
    }
}

@Composable
fun rememberOnlineGate(): OnlineGate {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val gate = remember(context) { OnlineGate(context) { what, go -> pending = what to go } }
    pending?.let { (what, go) ->
        ConfirmDialog(
            title = "$what needs the internet",
            // A caption's words are named here because this is the dialog
            // Translate captions puts up, and translating sends the line itself
            // (OnlineTranslate.translatePiece). Settings' own card was changed
            // to say so when translation was added; this copy - the one the
            // person actually agrees to - was left saying only the search and
            // the picked name, which was not true for the one tool whose
            // request is the person's own words.
            body = "Squish will connect to fetch it. Only the search you type, a caption's words if you ask for a " +
                "translation, and the name of what you pick are sent - never your videos, photos or projects.",
            caution = "This turns on online features. Settings › Online can turn them off again at any time.",
            confirmLabel = "Continue",
            dismissLabel = "Not now",
            icon = Icons.Filled.Public,
            accent = SquishColors.Cyan,
            destructive = false,
            onConfirm = {
                Online.setEnabled(context, true)
                pending = null
                go()
            },
            onDismiss = { pending = null }
        )
    }
    return gate
}
