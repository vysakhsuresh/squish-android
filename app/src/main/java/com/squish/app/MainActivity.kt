package com.squish.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.squish.app.navigation.SquishNavHost
import com.squish.app.ui.theme.SquishTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Installs the animated launch icon as the system splash, then hands
        // straight to the dashboard. No second in-app splash.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: a restored activity is already wherever the
        // video took it, and opening it again would stack a second editor.
        val opened = if (savedInstanceState == null) videoFrom(intent) else null
        setContent {
            SquishTheme {
                SquishNavHost(openVideo = opened)
            }
        }
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
