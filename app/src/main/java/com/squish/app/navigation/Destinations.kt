package com.squish.app.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Library : Destination("library")
    data object Drafts : Destination("drafts")
    data object Settings : Destination("settings")

    /**
     * [resume] is set only by the drafts list: choosing a draft there means "carry
     * on with that edit", so it opens as it was left rather than as the bare video
     * with an offer to restore it.
     */
    data object Editor : Destination("editor/{videoUri}?resume={resume}") {
        fun buildRoute(encodedUri: String, resume: Boolean = false) = "editor/$encodedUri?resume=$resume"
    }

    /**
     * [slot] is the file the session saves into - a fresh one for a dashboard
     * tap, the draft's own for the drafts list, which is also the only caller
     * that sets [resume]. Carried in the route so it survives process death
     * with the screen: a session that came back under a different name would
     * write a second draft beside the one it was.
     */
    data object QuickTool : Destination("tool/{toolId}?slot={slot}&resume={resume}") {
        fun buildRoute(toolId: String, encodedSlot: String, resume: Boolean = false) =
            "tool/$toolId?slot=$encodedSlot&resume=$resume"
    }

    /**
     * The done screen. It carries what was done as well as what came out, because
     * "Compress another video" after a merge is the app telling you it was not
     * paying attention - and where back leads, because the screen that made the
     * file is still underneath and worth going back to.
     */
    data object Export : Destination("export/{resultPath}/{job}?back={back}") {
        fun buildRoute(encodedPath: String, encodedJob: String, encodedBackLabel: String) =
            "export/$encodedPath/$encodedJob?back=$encodedBackLabel"
    }
}
