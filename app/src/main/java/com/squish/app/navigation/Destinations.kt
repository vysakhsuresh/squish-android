package com.squish.app.navigation

sealed class Destination(val route: String) {
    data object Splash : Destination("splash")
    data object Home : Destination("home")
    data object History : Destination("history")
    data object Settings : Destination("settings")

    data object Editor : Destination("editor/{videoUri}") {
        fun buildRoute(encodedUri: String) = "editor/$encodedUri"
    }

    data object Export : Destination("export/{resultPath}") {
        fun buildRoute(encodedPath: String) = "export/$encodedPath"
    }
}
