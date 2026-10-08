package com.squish.app.navigation

sealed class Destination(val route: String) {
    /** Home, Tools and Library in one: HomeShell, and HomeTab for why. */
    data object Home : Destination("home")
    data object Drafts : Destination("drafts")
    data object Settings : Destination("settings")

    /**
     * One export, on a screen of its own: what it is, where it went, and the
     * ways to share or delete it. A library row used to open the done screen
     * - a tick springing in over a file made weeks ago.
     */
    data object LibraryItem : Destination("library/{recordId}") {
        fun buildRoute(recordId: String) = "library/$recordId"
    }

    /**
     * The editor, on a project (ProjectRules.newId). The id is the draft's
     * slot on disk, so the same route reopens the same project after the
     * process is killed; a project not yet saved opens on the files staged for
     * it (ProjectAutosave.stageStart). It used to carry the video's URI, which
     * made a project the same thing as its first video.
     */
    data object Editor : Destination("editor/{projectId}") {
        fun buildRoute(projectId: String) = "editor/$projectId"
    }

    /**
     * [slot] is the file the session saves into - a fresh one for a dashboard
     * tap, the draft's own for the drafts list, which is also the only caller
     * that sets [resume]. Carried in the route so it survives process death
     * with the screen: a session that came back under a different name would
     * write a second draft beside the one it was. The screen reloads whatever
     * its slot holds, whichever way it was opened - that is what brings a
     * dashboard session back after a kill - so [resume] only records where the
     * session was opened from.
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
