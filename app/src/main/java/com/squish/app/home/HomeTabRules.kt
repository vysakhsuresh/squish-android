package com.squish.app.home

/**
 * Which of the dashboard's three places you are in.
 *
 * The dashboard used to be one scroll: the way in, then every project, then the
 * quick tools, then a door to the tool sessions and a door to the library. That
 * puts the tools and the library *behind* the projects - and the projects grow.
 * At nineteen of them the "One job, one tap" tiles were four full swipes down
 * and the Library door was past those, so the longer the app was used the
 * further away its own tools got. The things that should never move were the
 * ones that moved most.
 *
 * These three are destinations, not sections of a page, so they are a bar at
 * the thumb's end of the phone and each is one tap from the other two, at any
 * number of projects. The bar is only ever on these three; the editor, the
 * quick tools, the export flow and Settings - where vertical room is actually
 * short - never see it.
 */
enum class HomeTab(val label: String) {
    Home("Home"),
    Tools("Tools"),
    Library("Library")
}

object HomeTabRules {
    /** Where the system back gesture lands, or null to leave the app. */
    fun backLandsOn(current: HomeTab): HomeTab? =
        if (current == HomeTab.Home) null else HomeTab.Home

    /**
     * A tap on the place you are already in scrolls that list back to the top
     * rather than doing nothing. It is the one gesture that would otherwise be
     * a long swipe, and ten rows of project cards is a long swipe.
     */
    fun retapScrollsToTop(current: HomeTab, tapped: HomeTab): Boolean = current == tapped

    /**
     * A quiet dot on Tools when something is waiting behind it - an unfinished
     * quick-tool session, or something still in the bin. A number there would
     * be clutter: what matters is only whether there is anything at all, since
     * that is the whole question the old door's subtitle answered.
     *
     * Nothing is marked on Home or Library. Home is where a tap already lands,
     * and an export is finished work rather than a thing to come back to.
     */
    fun markedTab(unfinishedSessions: Int, binned: Int): HomeTab? =
        if (unfinishedSessions > 0 || binned > 0) HomeTab.Tools else null
}
