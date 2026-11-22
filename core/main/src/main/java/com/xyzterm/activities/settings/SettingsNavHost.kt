package com.xyzterm.activities.settings

import androidx.compose.animation.core.isReduceMotionEnabled
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.xyzterm.animations.NavigationAnimationTransitions
import com.xyzterm.settings.SettingsRegistry
import com.xyzterm.settings.SettingsScreen
import com.xyzterm.settings.about.AboutScreen
import com.xyzterm.settings.app.SettingsAppScreen
import com.xyzterm.settings.debugOptions.AppLogs
import com.xyzterm.settings.debugOptions.DeveloperOptions
import com.xyzterm.settings.keybinds.KeybindingsScreen
import com.xyzterm.settings.language.LanguageScreen
import com.xyzterm.settings.support.Support
import com.xyzterm.settings.theme.ThemeScreen

@Composable
fun SettingsNavHost(navController: NavHostController, activity: SettingsActivity) {
    val reduceMotion = isReduceMotionEnabled()
    NavHost(
        navController = navController,
        startDestination = SettingsRoutes.Settings.route,
        enterTransition = { NavigationAnimationTransitions.enterTransition(reduceMotion) },
        exitTransition = { NavigationAnimationTransitions.exitTransition(reduceMotion) },
        popEnterTransition = { NavigationAnimationTransitions.popEnterTransition(reduceMotion) },
        popExitTransition = { NavigationAnimationTransitions.popExitTransition(reduceMotion) },
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
