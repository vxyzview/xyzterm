package com.xyzterm

import android.app.Application
import android.content.Intent
import com.xyzterm.activities.settings.SettingsRoutes
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.commands.CommandProvider
import com.xyzterm.commands.ToolbarConfiguration
import com.xyzterm.commands.global.TerminalCommand
import com.xyzterm.exec.pendingCommand
import com.xyzterm.exec.ubuntuProcess
import com.xyzterm.extension.api.DynamicRoute
import com.xyzterm.feature.Feature
import com.xyzterm.feature.FeatureToggle
import com.xyzterm.resources.drawables
import com.xyzterm.resources.strings
import com.xyzterm.settings.SettingsCategory
import com.xyzterm.settings.SettingsRegistry
import com.xyzterm.settings.editor.TerminalFontScreen
import com.xyzterm.settings.terminal.SettingsTerminalScreen
import com.xyzterm.settings.terminal.SnippetsScreen
import com.xyzterm.settings.terminal.TerminalAdvancedSettings
import com.xyzterm.settings.terminal.TerminalAppearanceSettings
import com.xyzterm.settings.terminal.TerminalBackupsScreen
import com.xyzterm.settings.terminal.TerminalCheckScreen
import com.xyzterm.settings.terminal.TerminalDesktopScreen
import com.xyzterm.settings.terminal.TerminalDataSettings
import com.xyzterm.settings.terminal.TerminalExtraKeys
import com.xyzterm.settings.terminal.TerminalInputSettings
import com.xyzterm.settings.terminal.UserBindingsScreen
import com.xyzterm.utils.toast

/**
 * This build only ships the terminal feature. The file-manager/drawer and code-runner integrations that used to live
 * here (add-project shortcut, "open in terminal" file action, and the UniversalRunner hookup into :features:runner)
 * were removed along with the editor UI they depended on.
 */
class TerminalFeature : Feature {
    override val toggle =
        FeatureToggle(
            nameRes = strings.terminal_feature,
            key = "feature_terminal",
            default = true,
            iconRes = drawables.terminal,
        )

    private var settingsCategory: SettingsCategory? = null
    private val routes = mutableListOf<DynamicRoute>()

    override fun init(application: Application) {

        // Register settings categories
        settingsCategory =
            SettingsCategory(
                    labelRes = strings.terminal,
                    descriptionRes = strings.terminal_desc,
                    iconRes = drawables.terminal,
                    route = SettingsRoutes.TerminalSettings.route,
                )
                .also { SettingsRegistry.registerCategory(it) }

        // Register settings routes
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalSettings.route) { navController, _ ->
                SettingsTerminalScreen(navController)
            },
        )
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalAppearance.route) { navController, _ ->
                TerminalAppearanceSettings(navController)
            },
        )
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalInput.route) { navController, _ ->
                TerminalInputSettings(navController)
            },
        )
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalData.route) { navController, _ -> TerminalDataSettings(navController) },
        )
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalAdvanced.route) { navController, _ ->
                TerminalAdvancedSettings(navController)
            },
        )
        routes.add(DynamicRoute(SettingsRoutes.TerminalExtraKeys.route) { _, _ -> TerminalExtraKeys() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalSnippets.route) { _, _ -> SnippetsScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalCheck.route) { _, _ -> TerminalCheckScreen() })
        routes.add(
            DynamicRoute(SettingsRoutes.TerminalDesktop.route) { navController, _ ->
                TerminalDesktopScreen(navController)
            }
        )
        routes.add(DynamicRoute(SettingsRoutes.TerminalFontScreen.route) { _, _ -> TerminalFontScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalBackups.route) { _, _ -> TerminalBackupsScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalBinds.route) { _, _ -> UserBindingsScreen() })

        routes.forEach { SettingsRegistry.registerRoute(it) }

        // Register TerminalLauncher handler
        TerminalLauncher.handler = { activity, sandbox, exe, args, id, terminatePreviousSession, workingDir, env ->
            pendingCommand =
                com.xyzterm.exec.TerminalCommand(
                    sandbox = sandbox,
                    exe = exe,
                    args = args,
                    id = id,
                    terminatePreviousSession = terminatePreviousSession,
                    workingDir = workingDir,
                    env = env,
                )
            try {
                val intent = Intent(activity, Terminal::class.java)
                activity.startActivity(intent)
            } catch (_: Exception) {
                toast("Terminal feature is not available in this build")
            }
        }

        // Register SandboxedProcessRegistry provider
        SandboxedProcessRegistry.provider = { command, workingDir, excludeMounts ->
            ubuntuProcess(excludeMounts, workingDir = workingDir, command = command)
        }

        // Register global command
        CommandProvider.registerCommand(TerminalCommand)

        // Assuming there's at least one item already there
        ToolbarConfiguration.addGlobalToolbarCommand(TerminalCommand, index = 1)
    }

    override fun dispose(application: Application) {
        settingsCategory?.let { SettingsRegistry.unregisterCategory(it) }
        routes.forEach { SettingsRegistry.unregisterRoute(it) }
        routes.clear()

        TerminalLauncher.handler = null
        SandboxedProcessRegistry.provider = null
        CommandProvider.unregisterCommand(TerminalCommand)
        ToolbarConfiguration.removeGlobalToolbarCommand(TerminalCommand)
    }
}
