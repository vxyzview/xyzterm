package com.xyzterm.settings.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.xyzterm.TerminalLauncher
import com.xyzterm.components.InfoBlock
import com.xyzterm.components.PreferenceList
import com.xyzterm.components.PreferenceSingleInput
import com.xyzterm.components.SettingsItem
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.file.child
import com.xyzterm.file.localBinDir
import com.xyzterm.resources.strings
import com.xyzterm.terminal.DesktopSessions
import com.xyzterm.settings.Settings

@Composable
fun TerminalDesktopScreen(navController: NavController) {
    val context = LocalContext.current
    PreferenceLayout(label = stringResource(strings.desktop), backArrowVisible = true) {
        PreferenceGroup(heading = stringResource(strings.desktop)) {
            InfoBlock(text = stringResource(strings.desktop_requires_x11))

            PreferenceList(
                label = stringResource(strings.desktop_session),
                description = null,
                items =
                    listOf(
                        DesktopSessions.AUTO to "Auto-detect",
                        DesktopSessions.CUSTOM to "Custom command",
                    ) + DesktopSessions.all.map { it.key to it.label },
                selectedItem = Settings.desktop_session,
                onItemSelected = { Settings.desktop_session = it },
            )

            if (Settings.desktop_session == DesktopSessions.CUSTOM) {
                PreferenceSingleInput(
                    label = "Session command",
                    description = Settings.desktop_custom.ifEmpty { "e.g. startxfce4 or labwc" },
                    value = Settings.desktop_custom,
                    validate = { if (it.isBlank()) "Enter a command" else null },
                    onConfirm = { Settings.desktop_custom = it },
                )
            }

            PreferenceSingleInput(
                label = stringResource(strings.desktop_display),
                description = Settings.x11_display,
                value = Settings.x11_display,
                validate = { if (it.startsWith(":")) null else "Use a display like :0" },
                onConfirm = { Settings.x11_display = it },
            )

            SettingsItem(
                label = stringResource(strings.desktop_start),
                description = stringResource(strings.desktop_desc),
                showSwitch = false,
                default = false,
                sideEffect = {
                    val activity = context as? android.app.Activity ?: return@SettingsItem
                    TerminalLauncher.launch(
                        activity = activity,
                        sandbox = true,
                        exe = localBinDir().child("start-desktop").absolutePath,
                        id = "desktop",
                        env =
                            arrayOf(
                                "X11_DISPLAY=${Settings.x11_display}",
                                "X11_SESSION=${Settings.desktop_session}",
                                "X11_CUSTOM=${Settings.desktop_custom}",
                            ),
                    )
                },
            )

            SettingsItem(
                label = stringResource(strings.gamepad_input),
                description = stringResource(strings.gamepad_input_desc),
                default = Settings.gamepad_input,
                sideEffect = { Settings.gamepad_input = it },
            )
        }
    }
}
