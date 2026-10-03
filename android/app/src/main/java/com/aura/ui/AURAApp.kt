package com.aura.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aura.ui.screens.DashboardScreen
import com.aura.ui.screens.HomeScreen
import com.aura.ui.screens.PermissionsScreen
import com.aura.ui.screens.SettingsScreen
import com.aura.ui.screens.SkillStoreScreen
import com.aura.ui.screens.TaskLogScreen

object AURADestinations {
    const val HOME        = "home"
    const val TASK_LOG    = "task_log"
    const val SETTINGS    = "settings"
    const val SKILL_STORE = "skill_store"
    const val DASHBOARD   = "dashboard"
    const val PERMISSIONS = "permissions"
}

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
                onNavigateToSettings = { navController.navigate(AURADestinations.SETTINGS) },
                onNavigateToSkillStore = { navController.navigate(AURADestinations.SKILL_STORE) },
                onNavigateToDashboard = { navController.navigate(AURADestinations.DASHBOARD) }
            )
        }
        composable(AURADestinations.TASK_LOG) {
            TaskLogScreen(onBack = { navController.popBackStack() })
        }
        composable(AURADestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPermissions = { navController.navigate(AURADestinations.PERMISSIONS) }
            )
        }
        composable(AURADestinations.SKILL_STORE) {
            SkillStoreScreen(onBack = { navController.popBackStack() })
        }
        composable(AURADestinations.DASHBOARD) {
            DashboardScreen(onBack = { navController.popBackStack() })
        }
        composable(AURADestinations.PERMISSIONS) {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
