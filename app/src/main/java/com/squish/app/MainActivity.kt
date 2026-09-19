package com.squish.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            SquishTheme {
                SquishNavHost()
            }
        }
    }
}
