package com.rk.settings.terminal

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import com.rk.activities.terminal.Terminal
import com.rk.exec.isTerminalInstalled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rk.DocumentProvider
import com.rk.DefaultScope
import com.rk.activities.settings.SettingsActivity
import com.rk.activities.settings.SettingsRoutes
import com.rk.activities.settings.settingsNavController
import com.rk.components.NextScreenCard
import com.rk.components.PreferenceList
import com.rk.components.RoundedValueSlider
import com.rk.components.SettingsItem
import com.rk.components.SteppedValueSlider
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.switch.PreferenceSwitch
import com.rk.feature.FeatureRegistry
import com.rk.file.TERMINAL_SETUP_OK_MARKER
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.localLibDir
import com.rk.file.sandboxDir
import com.rk.file.toFileObject
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.SessionService
import com.rk.terminal.TerminalBackup
import com.rk.terminal.terminalView
import com.rk.utils.LoadingPopup
import com.rk.utils.dialogRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.utils.dpToPx
import com.rk.utils.getTempDir
import com.rk.utils.toast
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

enum class TerminalCursorStyle(val value: String, val stringRes: Int) {
    BLOCK("block", strings.block),
    BAR("bar", strings.bar),
    UNDERLINE("underline", strings.underline);

    companion object {
        fun fromString(value: String): TerminalCursorStyle {
            return entries.firstOrNull { it.value == value } ?: BLOCK
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun SettingsTerminalScreen() {
    PreferenceLayout(label = stringResource(id = strings.terminal), backArrowVisible = true) {
        val context = LocalContext.current
        val activity = LocalActivity.current as? AppCompatActivity
        // State-backed so uninstall/restore updates the card immediately.
        var terminalInstalled by remember { mutableStateOf(isTerminalInstalled()) }

        PreferenceGroup(heading = stringResource(strings.advanced)) {
            if (FeatureRegistry.isEnabled("debug_mode")) {
                SettingsItem(
                    label = stringResource(strings.failsafe_mode),
                    description = stringResource(strings.failsafe_mode_desc),
                    default = !Settings.sandbox,
                    sideEffect = { Settings.sandbox = !it },
                )

                PreferenceList(
                    label = "SECCOMP",
                    description = stringResource(strings.seccomp_desc),
                    items =
                        listOf(
                            "unspecified" to stringResource(strings.seccomp_unspecified),
                            "no" to stringResource(strings.seccomp_no_seccomp),
                            "yes" to stringResource(strings.seccomp_seccomp),
                        ),
                    selectedItem = Settings.seccomp_mode,
                    onItemSelected = { Settings.seccomp_mode = it },
                )
            }

            NextScreenCard(
                label = stringResource(strings.terminal_health),
                description = stringResource(strings.terminal_health_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalCheck,
            )
        }

        PreferenceGroup(heading = stringResource(strings.appearance)) {
            SteppedValueSlider(
                label = stringResource(strings.text_size),
                min = 10,
                max = 20,
                default = Settings.terminal_font_size,
                onValueChanged = {
                    Settings.terminal_font_size = it
                    terminalView.get()?.setTextSize(dpToPx(it.toFloat(), context))
                },
            )

            NextScreenCard(
                label = stringResource(strings.manage_terminal_font),
                description = stringResource(strings.manage_terminal_font_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalFontScreen,
            )

            PreferenceList(
                label = stringResource(strings.cursor_style),
                description = stringResource(strings.cursor_style_desc),
                items = TerminalCursorStyle.entries.map { it to stringResource(it.stringRes) },
                selectedItem = TerminalCursorStyle.fromString(Settings.terminal_cursor_style),
                onItemSelected = { Settings.terminal_cursor_style = it.value },
            )
        }

        PreferenceGroup(heading = stringResource(strings.user_data)) {
            // Ubuntu install is optional now: show a one-tap entry that opens the
            // terminal's opt-in install screen instead of forcing the download.
            if (!terminalInstalled) {
                SettingsItem(
                    label = stringResource(strings.install),
                    description = stringResource(strings.install_ubuntu_optional_desc),
                    showSwitch = false,
                    default = false,
                    sideEffect = { context.startActivity(Intent(context, Terminal::class.java)) },
                )
            }

            val restore =
                rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri == null) {
                        return@rememberLauncherForActivityResult
                    }

                    val loading = LoadingPopup(activity, null)
                    loading.show()

                    DefaultScope.launch(Dispatchers.IO) {
                        // Re-check at execution time: the picker was open while
                        // sessions could have been spawned elsewhere.
                        if (SessionService.sessionsMayExist()) {
                            withContext(Dispatchers.Main + NonCancellable) {
                                runCatching { loading.hide() }
                                toast(strings.close_sessions_first)
                            }
                            return@launch
                        }

                        val fileObject = uri.toFileObject(expectedIsFile = true)

                        val tempFile = getTempDir().child("terminal-backup.tar.gz")

                        try {
                            fileObject.getInputStream().use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // Extraction happens into a staging dir inside restore();
                            // the live sandbox is only replaced on success.
                            val error = TerminalBackup.restore(tempFile)

                            withContext(Dispatchers.Main + NonCancellable) {
                                runCatching { loading.hide() }
                                if (error == null) {
                                    terminalInstalled = isTerminalInstalled()
                                    // Running sessions keep the old rootfs mapped.
                                    toast(strings.restart_required)
                                } else {
                                    toast(strings.setup_failed.getFilledString(error))
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main + NonCancellable) {
                                runCatching { loading.hide() }
                                toast(strings.setup_failed.getFilledString(e.message))
                            }
                        } finally {
                            withContext(NonCancellable) { tempFile.delete() }
                        }
                    }
                }

            SettingsItem(
                label = stringResource(strings.backup),
                description = stringResource(strings.terminal_backup),
                showSwitch = false,
                default = false,
                sideEffect = {
                    (activity as? SettingsActivity)?.fileManager?.let { fileManager ->
                        fileManager.createNewFile(
                            mimeType = "application/octet-stream",
                            title = "terminal-backup.tar.gz",
                        ) { fileObject ->
                            DefaultScope.launch(Dispatchers.IO) {
                                if (fileObject != null) {
                                    val targetFile = getTempDir().child("terminal-backup.tar.gz")

                                    val loading = LoadingPopup(activity, null)
                                    withContext(Dispatchers.Main) { runCatching { loading.show() } }

                                    try {
                                        val ok = TerminalBackup.create(targetFile)

                                        // Copy into the SAF target first, then toast:
                                        // success must mean the archive actually landed.
                                        var copied = false
                                        if (ok) {
                                            copied =
                                                runCatching {
                                                    targetFile.inputStream().use { inputStream ->
                                                        fileObject.getOutputStream(false).use { outputStream ->
                                                            inputStream.copyTo(outputStream)
                                                        }
                                                    }
                                                }
                                                    .onFailure { it.printStackTrace() }
                                                    .isSuccess
                                        }

                                        withContext(Dispatchers.Main + NonCancellable) {
                                            runCatching { loading.hide() }
                                            when {
                                                ok && copied -> toast(strings.success)
                                                ok -> toast(strings.export_failed)
                                                else -> toast(strings.failed)
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main + NonCancellable) {
                                            runCatching { loading.hide() }
                                            toast(strings.setup_failed.getFilledString(e.message))
                                        }
                                    } finally {
                                        withContext(NonCancellable) { targetFile.delete() }
                                    }
                                }
                            }
                        }
                    }
                },
            )

            SettingsItem(
                label = stringResource(strings.restore),
                description = stringResource(strings.restore_terminal),
                showSwitch = false,
                default = false,
                sideEffect = {
                    // Same session guard as the backups screen: restoring replaces
                    // the whole sandbox, so never launch the picker with live sessions.
                    if (SessionService.sessionsMayExist()) {
                        dialogRes(
                            activity = activity,
                            title = strings.attention.getString(),
                            msg = strings.close_sessions_first.getString(),
                            onCancel = {},
                        )
                    } else {
                        dialogRes(
                            activity = activity,
                            title = strings.restore.getString(),
                            msg = strings.restore_terminal.getString(),
                            okRes = strings.restore,
                            onCancel = {},
                            onOk = { restore.launch("*/*") },
                        )
                    }
                },
            )

            SettingsItem(
                label = stringResource(strings.auto_backup),
                description = stringResource(strings.auto_backup_desc),
                default = Settings.auto_backup,
                sideEffect = { Settings.auto_backup = it },
            )

            NextScreenCard(
                label = stringResource(strings.manage_backups),
                description = stringResource(strings.manage_backups_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalBackups,
            )

            NextScreenCard(
                label = stringResource(strings.custom_binds),
                description = stringResource(strings.custom_binds_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalBinds,
            )
        }

        // Destructive: visually separated from the data rows above.
        PreferenceGroup {
            SettingsItem(
                label = stringResource(strings.uninstall),
                default = false,
                description = stringResource(strings.uninstall_terminal),
                showSwitch = false,
                startWidget = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                sideEffect = {
                    val hasSessions =
                        Terminal.instance?.sessionBinder?.get()?.getService()?.sessionList?.isNotEmpty()
                            ?: SessionService.sessionsMayExist()
                    if (hasSessions) {
                        dialogRes(
                            activity = activity,
                            title = strings.attention.getString(),
                            msg = strings.close_sessions_first.getString(),
                            onCancel = {},
                        )
                    } else {
                        dialogRes(
                            activity = activity,
                            title = strings.attention.getString(),
                            msg = strings.uninstall_terminal_warning.getString(),
                            onCancel = {},
                            okRes = strings.uninstall,
                            onOk = {
                                // Re-check at execution time: the confirm dialog
                                // was open while sessions could have been spawned.
                                if (SessionService.sessionsMayExist()) {
                                    dialogRes(
                                        activity = activity,
                                        title = strings.attention.getString(),
                                        msg = strings.close_sessions_first.getString(),
                                        onCancel = {},
                                    )
                                } else {
                                    DefaultScope.launch(Dispatchers.IO) {
                                    val loading = LoadingPopup(activity, null)
                                    withContext(Dispatchers.Main) { runCatching { loading.show() } }
                                    runCatching {
                                        loading.setMessage(strings.deleting.getFilledString("binaries"))
                                        localBinDir().deleteRecursively()
                                        loading.setMessage(strings.deleting.getFilledString("libraries"))
                                        localLibDir().deleteRecursively()
                                        loading.setMessage(strings.deleting.getFilledString("sandbox"))
                                        sandboxDir().deleteRecursively()
                                        localDir().child(TERMINAL_SETUP_OK_MARKER).delete()
                                        // Saved sessions would restore dead cwds
                                        // into a future reinstall.
                                        SessionService.clearSavedSessions()
                                    }
                                    terminalInstalled = false
                                    withContext(Dispatchers.Main + NonCancellable) {
                                        runCatching { loading.hide() }
                                        toast(strings.success)
                                    }
                                }
                                }
                            },
                        )
                    }
                },
            )
        }

        PreferenceGroup(heading = stringResource(strings.input)) {
            SettingsItem(
                label = stringResource(strings.show_extra_keys),
                description = stringResource(strings.show_extra_keys_desc),
                default = Settings.terminal_show_extra_keys,
                sideEffect = { Settings.terminal_show_extra_keys = it },
            )

            NextScreenCard(
                label = stringResource(strings.change_extra_keys),
                description = stringResource(strings.change_extra_keys_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalExtraKeys,
            )

            NextScreenCard(
                label = stringResource(strings.manage_snippets),
                description = stringResource(strings.manage_snippets_desc),
                navController = settingsNavController.get(),
                route = SettingsRoutes.TerminalSnippets,
            )

            SettingsItem(
                label = stringResource(strings.clipboard_keybindings),
                description = stringResource(strings.clipboard_keybindings_desc),
                default = Settings.terminal_clipboard_keybindings,
                sideEffect = { Settings.terminal_clipboard_keybindings = it },
            )

            var scrollbackInitial by remember { mutableStateOf(Settings.terminal_scrollback_buffer) }
            var scrollbackChanged by remember { mutableStateOf(false) }
            RoundedValueSlider(
                label = stringResource(strings.scrollback_buffer),
                description = stringResource(strings.scrollback_buffer_desc),
                min = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN,
                max = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX,
                default = Settings.terminal_scrollback_buffer,
                stepSize = 5_000,
            ) {
                Settings.terminal_scrollback_buffer = it
                scrollbackChanged = it != scrollbackInitial
            }

            if (scrollbackChanged) {
                Text(
                    text = stringResource(strings.restart_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SettingsItem(
                label = stringResource(strings.terminate_all_sessions),
                description = stringResource(strings.terminate_all_sessions_desc),
                default = Settings.terminate_sessions_on_exit,
                sideEffect = { Settings.terminate_sessions_on_exit = it },
            )

            var exposeHomeDirState by remember { mutableStateOf(Settings.expose_home_dir) }
            PreferenceSwitch(
                checked = exposeHomeDirState,
                onCheckedChange = {
                    if (it) {
                        dialogRes(
                            activity = activity,
                            title = strings.attention.getString(),
                            msg = strings.saf_expose_warning.getString(),
                            okRes = strings.continue_action,
                            onCancel = {},
                            onOk = {
                                Settings.expose_home_dir = true
                                DocumentProvider.setDocumentProviderEnabled(context, true)
                                exposeHomeDirState = true
                            },
                        )
                    } else {
                        Settings.expose_home_dir = false
                        exposeHomeDirState = false
                        DocumentProvider.setDocumentProviderEnabled(context, false)
                    }
                },
                label = stringResource(strings.expose_saf),
                description = stringResource(strings.expose_saf_desc),
            )
        }
    }
}
