package com.rk

import android.app.Application
import android.content.Intent
import com.rk.activities.settings.SettingsRoutes
import com.rk.activities.terminal.Terminal
import com.rk.commands.CommandProvider
import com.rk.commands.ToolbarConfiguration
import com.rk.commands.global.TerminalCommand
import com.rk.exec.pendingCommand
import com.rk.exec.ubuntuProcess
import com.rk.extension.api.DynamicRoute
import com.rk.feature.Feature
import com.rk.feature.FeatureToggle
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.settings.SettingsCategory
import com.rk.settings.SettingsRegistry
import com.rk.settings.editor.TerminalFontScreen
import com.rk.settings.terminal.SettingsTerminalScreen
import com.rk.settings.terminal.TerminalBackupsScreen
import com.rk.settings.terminal.TerminalCheckScreen
import com.rk.settings.terminal.TerminalExtraKeys
import com.rk.utils.toast

/**
 * This build only ships the terminal feature. The file-manager/drawer and code-runner
 * integrations that used to live here (add-project shortcut, "open in terminal" file action,
 * and the UniversalRunner hookup into :features:runner) were removed along with the editor UI
 * they depended on.
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
        routes.add(DynamicRoute(SettingsRoutes.TerminalSettings.route) { _, _ -> SettingsTerminalScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalExtraKeys.route) { _, _ -> TerminalExtraKeys() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalCheck.route) { _, _ -> TerminalCheckScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalFontScreen.route) { _, _ -> TerminalFontScreen() })
        routes.add(DynamicRoute(SettingsRoutes.TerminalBackups.route) { _, _ -> TerminalBackupsScreen() })

        routes.forEach { SettingsRegistry.registerRoute(it) }

        // Register TerminalLauncher handler
        TerminalLauncher.handler = { activity, sandbox, exe, args, id, terminatePreviousSession, workingDir, env ->
            pendingCommand =
                com.rk.exec.TerminalCommand(
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
