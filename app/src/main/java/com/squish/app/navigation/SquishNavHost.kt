package com.squish.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.squish.app.editor.EditorScreen
import com.squish.app.export.ExportScreen
import com.squish.app.history.LibraryScreen
import com.squish.app.home.HomeScreen
import com.squish.app.settings.SettingsScreen
import com.squish.app.tools.QuickTool
import com.squish.app.tools.QuickToolScreen

/**
 * Runs [block] only while this screen is still the one on top.
 *
 * A second tap that lands before the first navigation has finished is the reason
 * the editor crashed on a double press of back. Two pops go through, the second
 * one from a screen that is already on its way out; the entry it belonged to is
 * destroyed, and the screen composing against it asks for a view model that no
 * longer has anywhere to live. The whole navigation graph is routed through this
 * so no screen has to remember to guard itself.
 *
 * The check is the lifecycle rather than a timer, because what makes the second
 * tap wrong is not that it was fast - it is that the screen it came from had
 * already left.
 */
private inline fun NavBackStackEntry.once(block: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) block()
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
                    entry.once {
                        navController.navigate(Destination.Editor.buildRoute(Uri.encode(uri.toString())))
                    }
                },
                onOpenTool = { tool ->
                    entry.once { navController.navigate(Destination.QuickTool.buildRoute(tool.id)) }
                },
                onOpenLibrary = { entry.once { navController.navigate(Destination.Library.route) } },
                onOpenSettings = { entry.once { navController.navigate(Destination.Settings.route) } }
            )
        }

        composable(Destination.Library.route) { entry ->
            LibraryScreen(
                onBack = { entry.once { navController.popBackStack() } },
                onOpen = { path ->
                    entry.once {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode("Export"))
                        )
                    }
                }
            )
        }

        composable(Destination.Settings.route) { entry ->
            SettingsScreen(onBack = { entry.once { navController.popBackStack() } })
        }

        composable(
            route = Destination.QuickTool.route,
            arguments = listOf(navArgument("toolId") { type = NavType.StringType })
        ) { entry ->
            val tool = QuickTool.fromId(entry.arguments?.getString("toolId"))
            QuickToolScreen(
                tool = tool,
                onBack = { entry.once { navController.popBackStack() } },
                onExported = { path ->
                    entry.once {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode(tool.title))
                        ) {
                            popUpTo(Destination.Home.route)
                        }
                    }
                },
                onOpenInEditor = { uri ->
                    entry.once {
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
                onBack = { entry.once { navController.popBackStack() } },
                onExported = { path ->
                    entry.once {
                        navController.navigate(
                            Destination.Export.buildRoute(Uri.encode(path), Uri.encode("Export"))
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
                jobLabel = job.ifBlank { "Export" },
                onDone = {
                    entry.once {
                        navController.navigate(Destination.Home.route) {
                            popUpTo(Destination.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}
