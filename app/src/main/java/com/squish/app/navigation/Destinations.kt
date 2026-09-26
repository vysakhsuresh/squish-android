package com.squish.app.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Library : Destination("library")
    data object Drafts : Destination("drafts")
    data object Settings : Destination("settings")

    data object Editor : Destination("editor/{videoUri}") {
        fun buildRoute(encodedUri: String) = "editor/$encodedUri"
    }

    /** [resume] is set only by the drafts list; a dashboard tap always starts fresh. */
    data object QuickTool : Destination("tool/{toolId}?resume={resume}") {
        fun buildRoute(toolId: String, resume: Boolean = false) = "tool/$toolId?resume=$resume"
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
