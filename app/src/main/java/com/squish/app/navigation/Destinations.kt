package com.squish.app.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Library : Destination("library")
    data object Drafts : Destination("drafts")
    data object Settings : Destination("settings")

    data object Editor : Destination("editor/{videoUri}") {
        fun buildRoute(encodedUri: String) = "editor/$encodedUri"
    }

    data object QuickTool : Destination("tool/{toolId}") {
        fun buildRoute(toolId: String) = "tool/$toolId"
    }

    /**
      * The done screen. It carries what was done as well as what came out, because
      * "Compress another video" after a merge is the app telling you it was not
      * paying attention.
      */
    data object Export : Destination("export/{resultPath}/{job}") {
        fun buildRoute(encodedPath: String, encodedJob: String) = "export/$encodedPath/$encodedJob"
    }
}
