package com.squish.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.squish.app.media.keepReadAccess
import com.squish.app.navigation.OpenRequest
import com.squish.app.navigation.SquishNavHost
import com.squish.app.settings.Preferences
import com.squish.app.ui.theme.SquishTheme

class MainActivity : ComponentActivity() {

    /**
     * The video "Open with" or "Share" last asked for, whenever it asked. A
     * fresh start sets it from the launching intent; a running app is handed
     * the intent through onNewIntent instead (the activity is single-task, so
     * a second copy of the whole app - with its own editor and players - is
     * never started in the caller's task) - which used to be ignored outright,
     * so opening a file while Squish was open did nothing at all. What the
     * request does with the screens is the nav host's decision: a new project
     * on the file, and not while an export runs.
     */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installs the animated launch icon as the system splash, then hands
        // straight to the dashboard. No second in-app splash.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Dark bars, always. The default reads the phone's light/dark setting to
        // pick the icon colour, but this app is navy whatever the phone is set
        // to - so on a phone in light mode the clock and the gesture pill were
        // drawn dark on dark and vanished.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // Only on a fresh start: a restored activity is already wherever the
        // video took it, and opening it again would stack a second editor.
        // Restored, the request comes back from the saved state instead: one
        // held while an export ran was lost to a dark-mode switch or a kill,
        // and the file the app came forward for never opened. The nav host
        // keeps the stamp it acted on, so one already acted on stays done.
        openRequest = if (savedInstanceState == null) videoFrom(intent) else savedRequest(savedInstanceState)
        // Read once here rather than on every snap tick; see Preferences.hapticsOn.
        Preferences.editorDefaults(this)
        setContent {
            SquishTheme {
                // Every snap and long-press asks LocalHapticFeedback; providing a
                // gated one here turns the Settings switch off everywhere without
                // each strip and box knowing there is a switch.
                val system = LocalHapticFeedback.current
                val gated = remember(system) {
                    object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            if (Preferences.hapticsOn) system.performHapticFeedback(hapticFeedbackType)
                        }
                    }
                }
                CompositionLocalProvider(LocalHapticFeedback provides gated) {
                    SquishNavHost(open = openRequest)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openRequest?.let { request ->
            outState.putParcelable(KEY_OPEN_URI, request.uri)
            outState.putLong(KEY_OPEN_STAMP, request.stamp)
            outState.putBoolean(KEY_OPEN_PERSISTED, request.persisted)
        }
    }

    private fun savedRequest(state: Bundle): OpenRequest? {
        val uri = BundleCompat.getParcelable(state, KEY_OPEN_URI, Uri::class.java) ?: return null
        return OpenRequest(uri, state.getLong(KEY_OPEN_STAMP), state.getBoolean(KEY_OPEN_PERSISTED, true))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's intent, so a recreation after this sees the
        // file that was opened last rather than the one it was launched with.
        setIntent(intent)
        // Stamped, so the same file opened twice is two requests: the nav host
        // acts on a change, and an equal value would not be one.
        videoFrom(intent)?.let { openRequest = it }
    }

    /** The video handed over by "Open with" or "Share", if that is how the app was started. */
    private fun videoFrom(intent: Intent?): OpenRequest? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        // A file on the phone only. An https link handed over would have been
        // fetched by the player - going online with online features off.
        if (uri.scheme != "content" && uri.scheme != "file") return null
        // Kept past this session when the sender allows it, so a draft of the
        // video can still reopen it tomorrow. Most share only for now; those
        // are copied into the app's storage when the project opens
        // (MediaAccess.importCopy), since a draft on a grant that ended with
        // the process opened on nothing the next day.
        val persisted = uri.scheme == "file" || keepReadAccess(uri)
        return OpenRequest(uri, SystemClock.elapsedRealtimeNanos(), persisted)
    }

    private companion object {
        const val KEY_OPEN_URI = "squish.open.uri"
        const val KEY_OPEN_STAMP = "squish.open.stamp"
        const val KEY_OPEN_PERSISTED = "squish.open.persisted"
    }
}
