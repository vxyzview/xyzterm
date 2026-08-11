package com.rk.activities.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rk.animations.NavigationAnimationTransitions
import com.rk.settings.SettingsRegistry
import com.rk.settings.SettingsScreen
import com.rk.settings.about.AboutScreen
import com.rk.settings.app.SettingsAppScreen
import com.rk.settings.debugOptions.AppLogs
import com.rk.settings.debugOptions.DeveloperOptions
import com.rk.settings.keybinds.KeybindingsScreen
import com.rk.settings.language.LanguageScreen
import com.rk.settings.support.Support
import com.rk.settings.theme.ThemeScreen

@Composable
fun SettingsNavHost(navController: NavHostController, activity: SettingsActivity) {
    NavHost(
        navController = navController,
        startDestination = SettingsRoutes.Settings.route,
        enterTransition = { NavigationAnimationTransitions.enterTransition() },
        exitTransition = { NavigationAnimationTransitions.exitTransition() },
        popEnterTransition = { NavigationAnimationTransitions.popEnterTransition() },
        popExitTransition = { NavigationAnimationTransitions.popExitTransition() },
    ) {
        composable(SettingsRoutes.Settings.route) { SettingsScreen(navController) }
        composable(SettingsRoutes.AppSettings.route) { SettingsAppScreen(activity, navController) }
        composable(SettingsRoutes.Keybindings.route) { KeybindingsScreen() }

        composable(SettingsRoutes.About.route) { AboutScreen() }

        composable(SettingsRoutes.DeveloperOptions.route) { DeveloperOptions(navController = navController) }
        composable(SettingsRoutes.AppLogs.route) { AppLogs() }
        composable(SettingsRoutes.Support.route) { Support() }
        composable(SettingsRoutes.LanguageScreen.route) { LanguageScreen() }
        composable(SettingsRoutes.Themes.route) { ThemeScreen(navController) }

        SettingsRegistry.routes.forEach { customRoute ->
            composable(customRoute.route, arguments = customRoute.arguments) { backStackEntry ->
                customRoute.content(navController, backStackEntry)
            }
        }
    }
}
