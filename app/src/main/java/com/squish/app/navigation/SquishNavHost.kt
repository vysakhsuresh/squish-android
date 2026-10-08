package com.squish.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.squish.app.data.ProjectRules
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolAutosave
import com.squish.app.editor.EditorScreen
import com.squish.app.export.ExportScreen
import com.squish.app.media.ExportsInFlight
import com.squish.app.history.DraftsScreen
import com.squish.app.history.LibraryDetailScreen
import com.squish.app.home.HomeShell
import com.squish.app.home.HomeViewModel
import com.squish.app.settings.SettingsScreen
import com.squish.app.tools.QuickTool
import com.squish.app.tools.QuickToolScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [block] only while this screen is still the one on top of the back stack.
 *
 * A second tap that lands before the first navigation has happened is why the
 * editor crashed on a double press of back. Two pops go through, the second from
 * a screen already on its way out; the entry it belonged to is destroyed, and the
 * screen composing against it asks for a view model that no longer has anywhere
 * to live. The whole graph routes through this so no screen has to guard itself.
 *
 * The test is the back stack, not the lifecycle. Checking `RESUMED` looked
 * equivalent and was not: a result handed back by the photo picker arrives while
 * the activity is still on its way to resumed, so the entry is merely STARTED and
 * every "open this in the editor" was dropped on the floor. Whether this screen
 * is still the top of the stack is the thing actually being asked, it is true the
 * instant a picker returns, and it goes false the moment the first pop lands -
 * which is the whole point.
 */
private inline fun NavController.fromTopOf(entry: NavBackStackEntry, block: () -> Unit) {
    if (currentBackStackEntry === entry) block()
}

/**
 * A video handed over by "Open with" or "Share", [stamp]ed with when it was
 * asked for: the same file opened twice is two requests, and the host acts
 * on each. [persisted] says the grant on it outlives this process; one that
 * does not is copied into the app's storage when the project opens.
 */
data class OpenRequest(val uri: Uri, val stamp: Long, val persisted: Boolean = true)

@Composable
fun SquishNavHost(
    /** The video "Open with" or "Share" last asked for, to be shown in its editor. */
    open: OpenRequest? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val autosave = remember(context) { SquishRepositories.autosave(context) }

    // The request acted on last, so a recomposition does not act on it again.
    var actedOn by rememberSaveable { mutableStateOf<Long?>(null) }
    // Held while any screen is exporting. The export runs only while its
    // screen is in front: a new editor over it let the display sleep under
    // the rest of the render, and dropped the result when it finished, so the
    // file was in the gallery and nobody was told. It opens the moment the
    // export is done, over the done screen.
    val exporting by ExportsInFlight.any.collectAsState()
    LaunchedEffect(open, exporting) {
        if (open == null || exporting || actedOn == open.stamp) return@LaunchedEffect
        actedOn = open.stamp
        // A project of its own for every *edit*: two cuts of one video are two
        // projects, which is what the per-video keying was dropped for. But a
        // file opened from outside and then left alone is not an edit, and
        // opening it again used to make another identical card - nine of them
        // after an evening of driving the editor on one clip, and two for
        // anyone who taps "Open with" twice without doing anything.
        //
        // So: back to the one that is still just a look, if there is one. The
        // flag goes false the moment the edit differs from the one it was
        // opened with (ProjectAutosave.writeMeta), so this can never reopen
        // something that has been worked on.
        //
        // Over whatever is showing - the dashboard on a fresh start, another
        // edit when the app was already running - which stays underneath, its
        // draft saved as any edit's is on leaving.
        val id = withContext(Dispatchers.IO) {
            autosave.openedJustToLookOn(open.uri) ?: ProjectRules.newId().also {
                autosave.stageStart(it, listOf(open.uri), copyIn = !open.persisted, openedFromOutside = true)
            }
        }
        navController.navigate(Destination.Editor.buildRoute(id))
    }

    val scope = rememberCoroutineScope()
    /** Stages [uris] as a new project and opens the editor on it, over the dashboard. */
    fun openNewProject(uris: List<Uri>) {
        scope.launch {
            val id = ProjectRules.newId()
            withContext(Dispatchers.IO) { autosave.stageStart(id, uris) }
            navController.navigate(Destination.Editor.buildRoute(id)) { popUpTo(Destination.Home.route) }
        }
    }

    // Straight to the dashboard. The launch animation is the system splash, which
    // Android shows before this composes at all - routing through a second in-app
    // splash meant the same mark animated twice, back to back.
    NavHost(navController = navController, startDestination = Destination.Home.route) {

        composable(Destination.Home.route) { entry ->
            // Home, Tools and Library are three panes of one destination, not
            // three destinations: see HomeTab for why. The library had a route of
            // its own until then, and nothing navigates to it any more.
            HomeShell(
                onOpenProject = { id ->
                    navController.fromTopOf(entry) { navController.navigate(Destination.Editor.buildRoute(id)) }
                },
                onOpenTool = { tool ->
                    navController.fromTopOf(entry) {
                        // A slot of its own for every tap: the tool is being asked
                        // for fresh, and it must not write over a session waiting
                        // under Unfinished.
                        navController.navigate(
                            Destination.QuickTool.buildRoute(tool.id, Uri.encode(ToolAutosave.freshSlot(tool.id)))
                        )
                    }
                },
                onOpenDrafts = { navController.fromTopOf(entry) { navController.navigate(Destination.Drafts.route) } },
                onOpenExport = { recordId ->
                    navController.fromTopOf(entry) { navController.navigate(Destination.LibraryItem.buildRoute(recordId)) }
                },
                onOpenSettings = { navController.fromTopOf(entry) { navController.navigate(Destination.Settings.route) } }
            )
        }

        composable(
            route = Destination.LibraryItem.route,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { entry ->
            LibraryDetailScreen(
                recordId = entry.arguments?.getString("recordId").orEmpty(),
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } }
            )
        }

        composable(Destination.Drafts.route) { entry ->
            // The dashboard's view model owns the draft list and the discarding, so
            // this screen reads from that same instance rather than opening the
            // stores a second time - two readers of one folder would disagree the
            // moment either of them deleted anything.
            val homeEntry = remember(entry) { navController.getBackStackEntry(Destination.Home.route) }
            val homeViewModel: HomeViewModel = viewModel(homeEntry)
            val drafts by homeViewModel.toolDrafts.collectAsState()
            val trashed by homeViewModel.trashed.collectAsState()
            val undoOffer by homeViewModel.undoOffer.collectAsState()

            // Re-read on arrival: something may have been finished or thrown away
            // since the dashboard last looked.
            LaunchedEffect(Unit) { homeViewModel.refreshDrafts() }

            DraftsScreen(
                drafts = drafts,
                trashed = trashed,
                undoOffer = undoOffer,
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onOpenEdit = { draft ->
                    navController.fromTopOf(entry) { navController.navigate(Destination.Editor.buildRoute(draft.id)) }
                },
                onOpenTool = { draft ->
                    navController.fromTopOf(entry) {
                        val tool = QuickTool.fromId(draft.toolId)
                        navController.navigate(
                            Destination.QuickTool.buildRoute(tool.id, Uri.encode(draft.id), resume = true)
                        )
                    }
                },
                onDiscard = homeViewModel::discardDraft,
                onRevert = homeViewModel::revertDraft,
                onRestore = homeViewModel::restoreDraft,
                onPurge = homeViewModel::purgeDraft,
                onPurgeAll = homeViewModel::purgeAllTrashed,
                onDismissUndoOffer = homeViewModel::dismissUndoOffer
            )
        }

        composable(Destination.Settings.route) { entry ->
            SettingsScreen(onBack = { navController.fromTopOf(entry) { navController.popBackStack() } })
        }

        composable(
            route = Destination.QuickTool.route,
            arguments = listOf(
                navArgument("toolId") { type = NavType.StringType },
                navArgument("slot") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("resume") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            val tool = QuickTool.fromId(entry.arguments?.getString("toolId"))
            // A route with no slot - one written before slots existed and restored
            // after an update - falls back to the tool's old single file.
            val slot = entry.arguments?.getString("slot").orEmpty().ifBlank { tool.id }
            QuickToolScreen(
                tool = tool,
                slot = slot,
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onExported = { path ->
                    // The tool stays underneath, session and all: the done screen's
                    // back leads to it.
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Export.buildRoute(
                                Uri.encode(path), Uri.encode(tool.doneLabel), Uri.encode("Back to ${tool.title}")
                            )
                        )
                    }
                },
                onOpenInEditor = { uris ->
                    // The whole ordered list: a six-clip merge opened as a
                    // one-clip project before, since only the first was handed over.
                    navController.fromTopOf(entry) { openNewProject(uris) }
                }
            )
        }

        composable(
            route = Destination.Editor.route,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            EditorScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onExported = { path ->
                    // The editor stays on the stack with its timeline. Popping it
                    // here was half of how a successful export destroyed the edit;
                    // the other half was the draft being deleted.
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode("Exported"), Uri.encode("Back to editor"))
                        )
                    }
                }
            )
        }

        composable(
            route = Destination.Export.route,
            arguments = listOf(
                navArgument("resultPath") { type = NavType.StringType },
                navArgument("job") { type = NavType.StringType },
                navArgument("back") {
                    type = NavType.StringType
                    defaultValue = "Back"
                }
            )
        ) { entry ->
            // Decoded once already, by Navigation, when the route was matched.
            // Decoding again turned a document URI's "%3A" into ":", which is
            // a different URI, so nothing here decodes.
            val resultPath = entry.arguments?.getString("resultPath").orEmpty()
            val job = entry.arguments?.getString("job").orEmpty()
            val backLabel = entry.arguments?.getString("back").orEmpty()
            ExportScreen(
                resultPath = resultPath,
                jobLabel = job.ifBlank { "Exported" },
                backLabel = backLabel.ifBlank { "Back" },
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onDone = {
                    navController.fromTopOf(entry) {
                        navController.navigate(Destination.Home.route) {
                            popUpTo(Destination.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}
