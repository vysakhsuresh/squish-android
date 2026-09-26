package com.squish.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.squish.app.editor.EditorScreen
import com.squish.app.export.ExportScreen
import com.squish.app.history.DraftsScreen
import com.squish.app.history.LibraryScreen
import com.squish.app.home.HomeScreen
import com.squish.app.home.HomeViewModel
import com.squish.app.settings.SettingsScreen
import com.squish.app.tools.QuickTool
import com.squish.app.tools.QuickToolScreen

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

@Composable
fun SquishNavHost() {
    val navController = rememberNavController()

    // Straight to the dashboard. The launch animation is the system splash, which
    // Android shows before this composes at all - routing through a second in-app
    // splash meant the same mark animated twice, back to back.
    NavHost(navController = navController, startDestination = Destination.Home.route) {

        composable(Destination.Home.route) { entry ->
            HomeScreen(
                onOpenEditor = { uri ->
                    navController.fromTopOf(entry) {
                        navController.navigate(Destination.Editor.buildRoute(Uri.encode(uri.toString())))
                    }
                },
                onOpenTool = { tool ->
                    navController.fromTopOf(entry) { navController.navigate(Destination.QuickTool.buildRoute(tool.id)) }
                },
                onOpenLibrary = { navController.fromTopOf(entry) { navController.navigate(Destination.Library.route) } },
                onOpenDrafts = { navController.fromTopOf(entry) { navController.navigate(Destination.Drafts.route) } },
                onOpenSettings = { navController.fromTopOf(entry) { navController.navigate(Destination.Settings.route) } }
            )
        }

        composable(Destination.Library.route) { entry ->
            LibraryScreen(
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onOpen = { path ->
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode("Exported"))
                        )
                    }
                }
            )
        }

        composable(Destination.Drafts.route) { entry ->
            // The dashboard's view model owns the draft list and the discarding, so
            // this screen reads from that same instance rather than opening the
            // stores a second time - two readers of one folder would disagree the
            // moment either of them deleted anything.
            val homeEntry = remember(entry) { navController.getBackStackEntry(Destination.Home.route) }
            val homeViewModel: HomeViewModel = viewModel(homeEntry)
            val drafts by homeViewModel.drafts.collectAsState()

            // Re-read on arrival: something may have been finished or thrown away
            // since the dashboard last looked.
            LaunchedEffect(Unit) { homeViewModel.refreshDrafts() }

            DraftsScreen(
                drafts = drafts,
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onOpenEdit = { draft ->
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Editor.buildRoute(Uri.encode(draft.sourceUri.toString()))
                        )
                    }
                },
                onOpenTool = { tool ->
                    navController.fromTopOf(entry) {
                        navController.navigate(Destination.QuickTool.buildRoute(tool.id, resume = true))
                    }
                },
                onDiscard = homeViewModel::discardDraft
            )
        }

        composable(Destination.Settings.route) { entry ->
            SettingsScreen(onBack = { navController.fromTopOf(entry) { navController.popBackStack() } })
        }

        composable(
            route = Destination.QuickTool.route,
            arguments = listOf(
                navArgument("toolId") { type = NavType.StringType },
                navArgument("resume") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            val tool = QuickTool.fromId(entry.arguments?.getString("toolId"))
            QuickToolScreen(
                tool = tool,
                resume = entry.arguments?.getBoolean("resume") ?: false,
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onExported = { path ->
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode(tool.doneLabel))
                        ) {
                            popUpTo(Destination.Home.route)
                        }
                    }
                },
                onOpenInEditor = { uri ->
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Editor.buildRoute(Uri.encode(uri.toString()))
                        ) {
                            popUpTo(Destination.Home.route)
                        }
                    }
                }
            )
        }

        composable(
            route = Destination.Editor.route,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { entry ->
            val encoded = entry.arguments?.getString("videoUri").orEmpty()
            EditorScreen(
                sourceUri = Uri.parse(Uri.decode(encoded)),
                onBack = { navController.fromTopOf(entry) { navController.popBackStack() } },
                onExported = { path ->
                    navController.fromTopOf(entry) {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode("Exported"))
                        ) {
                            popUpTo(Destination.Home.route)
                        }
                    }
                }
            )
        }

        composable(
            route = Destination.Export.route,
            arguments = listOf(
                navArgument("resultPath") { type = NavType.StringType },
                navArgument("job") { type = NavType.StringType }
            )
        ) { entry ->
            val encoded = entry.arguments?.getString("resultPath").orEmpty()
            val job = Uri.decode(entry.arguments?.getString("job").orEmpty())
            ExportScreen(
                resultPath = Uri.decode(encoded),
                jobLabel = job.ifBlank { "Exported" },
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
