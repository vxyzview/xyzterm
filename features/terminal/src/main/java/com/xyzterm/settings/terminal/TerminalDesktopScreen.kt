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
                        "xfce4" to "XFCE",
                        "kde" to "KDE Plasma",
                        "openbox" to "Openbox",
                    ),
                selectedItem = Settings.desktop_session,
                onItemSelected = { Settings.desktop_session = it },
            )

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
