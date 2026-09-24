package com.aura.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aura.ui.screens.HomeScreen
import com.aura.ui.screens.SettingsScreen
import com.aura.ui.screens.TaskLogScreen

object AURADestinations {
    const val HOME = "home"
    const val TASK_LOG = "task_log"
    const val SETTINGS = "settings"
}

/**
 * Root composable that sets up navigation.
 * All screens are top-level destinations — simple flat navigation for MVP.
 */
@Composable
fun AURAApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AURADestinations.HOME
    ) {
        composable(AURADestinations.HOME) {
            HomeScreen(
                onNavigateToLog = { navController.navigate(AURADestinations.TASK_LOG) },
                onNavigateToSettings = { navController.navigate(AURADestinations.SETTINGS) }
            )
        }
        composable(AURADestinations.TASK_LOG) {
            TaskLogScreen(onBack = { navController.popBackStack() })
        }
        composable(AURADestinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
