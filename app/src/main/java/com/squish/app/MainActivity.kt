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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.squish.app.navigation.OpenRequest
import com.squish.app.navigation.SquishNavHost
import com.squish.app.ui.theme.SquishTheme

class MainActivity : ComponentActivity() {

    /**
     * The video "Open with" or "Share" last asked for, whenever it asked. A
     * fresh start sets it from the launching intent; a running app is handed
     * the intent through onNewIntent instead (the activity is single-task, so
     * a second copy of the whole app - with its own editor saving into the
     * same draft as the first's - is never started in the caller's task) -
     * which used to be ignored outright, so opening a file while Squish was
     * open did nothing at all. What the request does with the screens is the
     * nav host's decision: back to the editor already open on the video, or a
     * new one, and not while an export runs.
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
        if (savedInstanceState == null) openRequest = videoFrom(intent)?.let { OpenRequest(it, SystemClock.elapsedRealtimeNanos()) }
        setContent {
            SquishTheme {
                SquishNavHost(open = openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's intent, so a recreation after this sees the
        // file that was opened last rather than the one it was launched with.
        setIntent(intent)
        // Stamped, so the same file opened twice is two requests: the nav host
        // acts on a change, and an equal value would not be one.
        videoFrom(intent)?.let { openRequest = OpenRequest(it, SystemClock.elapsedRealtimeNanos()) }
    }

    /** The video handed over by "Open with" or "Share", if that is how the app was started. */
    private fun videoFrom(intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        // Kept past this session when the sender allows it, so a draft of the
        // video can still reopen it tomorrow. Most grant only for now; that works
        // until the app is closed.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return uri
    }
}
