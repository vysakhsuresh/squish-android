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
import com.squish.app.splash.SplashScreen

@Composable
fun SquishNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Destination.Splash.route
    ) {
        composable(Destination.Splash.route) {
            SplashScreen(
                onFinished = {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Destination.Home.route) {
            HomeScreen(
                onOpenVideo = { uri ->
                    navController.navigate(Destination.Editor.buildRoute(Uri.encode(uri.toString())))
                },
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
            route = Destination.Editor.route,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("videoUri").orEmpty()
            val uri = Uri.parse(Uri.decode(encoded))
            EditorScreen(
                sourceUri = uri,
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
            val path = Uri.decode(encoded)
            ExportScreen(
                resultPath = path,
                onDone = {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
