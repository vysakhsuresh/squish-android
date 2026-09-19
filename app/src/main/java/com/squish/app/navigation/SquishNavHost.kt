package com.squish.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.squish.app.editor.EditorScreen
import com.squish.app.export.ExportScreen
import com.squish.app.history.HistoryScreen
import com.squish.app.home.HomeScreen
import com.squish.app.settings.SettingsScreen
import com.squish.app.tools.QuickTool
import com.squish.app.tools.QuickToolScreen

@Composable
fun SquishNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Destination.Home.route) {

        composable(Destination.Home.route) {
            HomeScreen(
                onOpenEditor = { uri ->
                    navController.navigate(Destination.Editor.buildRoute(Uri.encode(uri.toString())))
                },
                onOpenTool = { tool -> navController.navigate(Destination.QuickTool.buildRoute(tool.id)) },
                onOpenHistory = { navController.navigate(Destination.History.route) },
                onOpenSettings = { navController.navigate(Destination.Settings.route) }
            )
        }

        composable(Destination.History.route) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Destination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Destination.QuickTool.route,
            arguments = listOf(navArgument("toolId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tool = QuickTool.fromId(backStackEntry.arguments?.getString("toolId"))
            QuickToolScreen(
                tool = tool,
                onBack = { navController.popBackStack() },
                onExported = { path ->
                    navController.navigate(Destination.Export.buildRoute(Uri.encode(path))) {
                        popUpTo(Destination.Home.route)
                    }
                },
                onOpenInEditor = { uri ->
                    navController.navigate(Destination.Editor.buildRoute(Uri.encode(uri.toString()))) {
                        popUpTo(Destination.Home.route)
                    }
                }
            )
        }

        composable(
            route = Destination.Editor.route,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("videoUri").orEmpty()
            EditorScreen(
                sourceUri = Uri.parse(Uri.decode(encoded)),
                onBack = { navController.popBackStack() },
                onExported = { path ->
                    navController.navigate(Destination.Export.buildRoute(Uri.encode(path))) {
                        popUpTo(Destination.Home.route)
                    }
                }
            )
        }

        composable(
            route = Destination.Export.route,
            arguments = listOf(navArgument("resultPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("resultPath").orEmpty()
            ExportScreen(
                resultPath = Uri.decode(encoded),
                onDone = {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
