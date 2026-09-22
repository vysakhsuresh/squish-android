package com.squish.app.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object History : Destination("history")
    data object Settings : Destination("settings")

    data object Editor : Destination("editor/{videoUri}") {
        fun buildRoute(encodedUri: String) = "editor/$encodedUri"
    }

    data object QuickTool : Destination("tool/{toolId}") {
        fun buildRoute(toolId: String) = "tool/$toolId"
    }

    data object Export : Destination("export/{resultPath}") {
        fun buildRoute(encodedPath: String) = "export/$encodedPath"
    }
}
