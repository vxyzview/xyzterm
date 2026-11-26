package com.xyzterm.settings.terminal

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.termux.terminal.TerminalEmulator
import com.xyzterm.DefaultScope
import com.xyzterm.DocumentProvider
import com.xyzterm.activities.settings.SettingsActivity
import com.xyzterm.activities.settings.SettingsRoutes
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.components.NextScreenCard
import com.xyzterm.components.PreferenceList
import com.xyzterm.components.RoundedValueSlider
import com.xyzterm.components.SettingsItem
import com.xyzterm.components.SingleInputDialog
import com.xyzterm.components.SteppedValueSlider
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.components.compose.preferences.switch.PreferenceSwitch
import com.xyzterm.exec.isTerminalInstalled
import com.xyzterm.feature.FeatureRegistry
import com.xyzterm.file.child
import com.xyzterm.file.localBinDir
import com.xyzterm.file.localDir
import com.xyzterm.file.localLibDir
import com.xyzterm.file.sandboxDir
import com.xyzterm.file.toFileObject
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.terminal.AutoBackupJobService
import com.xyzterm.terminal.AutoBackupPassphrase
import com.xyzterm.terminal.BackupCrypto
import com.xyzterm.terminal.PassphraseState
import com.xyzterm.terminal.TerminalBackup
import com.xyzterm.terminal.resetTerminalFiles
import com.xyzterm.terminal.terminalView
import com.xyzterm.utils.LoadingPopup
import com.xyzterm.utils.dialogRes
import com.xyzterm.utils.dpToPx
import com.xyzterm.utils.getTempDir
import com.xyzterm.utils.toast
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@Composable
fun SettingsTerminalScreen(navController: NavController) {
    PreferenceLayout(label = stringResource(id = strings.terminal), backArrowVisible = true) {
        PreferenceGroup(heading = stringResource(strings.appearance)) {
            NextScreenCard(
                label = stringResource(strings.appearance),
                description = stringResource(strings.terminal_desc),
                navController = navController,
                route = SettingsRoutes.TerminalAppearance,
            )
        }
        PreferenceGroup(heading = stringResource(strings.input)) {
            NextScreenCard(
                label = stringResource(strings.input),
                description = stringResource(strings.terminal_desc),
                navController = navController,
                route = SettingsRoutes.TerminalInput,
            )
        }
        PreferenceGroup(heading = stringResource(strings.user_data)) {
            NextScreenCard(
                label = stringResource(strings.user_data),
                description = stringResource(strings.terminal_desc),
                navController = navController,
                route = SettingsRoutes.TerminalData,
            )
        }
        PreferenceGroup(heading = stringResource(strings.advanced)) {
            NextScreenCard(
                label = stringResource(strings.advanced),
                description = stringResource(strings.terminal_desc),
                navController = navController,
                route = SettingsRoutes.TerminalAdvanced,
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun TerminalAdvancedSettings(navController: NavController) {
    PreferenceLayout(label = stringResource(strings.advanced), backArrowVisible = true) {
        val context = LocalContext.current
        val activity = LocalActivity.current as? AppCompatActivity
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
                navController = navController,
                route = SettingsRoutes.TerminalCheck,
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun TerminalAppearanceSettings(navController: NavController) {
    PreferenceLayout(label = stringResource(strings.appearance), backArrowVisible = true) {
        val context = LocalContext.current
        val activity = LocalActivity.current as? AppCompatActivity
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
                navController = navController,
                route = SettingsRoutes.TerminalFontScreen,
            )

            PreferenceList(
                label = stringResource(strings.cursor_style),
                description = stringResource(strings.cursor_style_desc),
                items = TerminalCursorStyle.entries.map { it to stringResource(it.stringRes) },
                selectedItem = TerminalCursorStyle.fromString(Settings.terminal_cursor_style),
                onItemSelected = { Settings.terminal_cursor_style = it.value },
            )

            SettingsItem(
                label = stringResource(strings.keep_screen_on),
                description = stringResource(strings.keep_screen_on_desc),
                default = Settings.terminal_keep_screen_on,
                sideEffect = {
                    Settings.terminal_keep_screen_on = it
                    // Apply live: both the view flag and the window flag set by
                    // the terminal host must follow, not just after a restart.
                    val window = Terminal.instance?.window
                    if (it) {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    terminalView.get()?.keepScreenOn = it
                },
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun TerminalDataSettings(navController: NavController) {
    PreferenceLayout(label = stringResource(strings.user_data), backArrowVisible = true) {
        val context = LocalContext.current
        val activity = LocalActivity.current as? AppCompatActivity
        // State-backed so uninstall/restore updates the card immediately.
        var terminalInstalled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { terminalInstalled = withContext(Dispatchers.IO) { isTerminalInstalled() } }

        var exportPassphrase by remember { mutableStateOf<String?>(null) }
        var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
        var restorePassphrase by remember { mutableStateOf("") }
        var autoBackupPhrase by remember { mutableStateOf("") }
        var showAutoBackupPhrase by remember { mutableStateOf(false) }

        fun launchExport(passphrase: String) {
            (activity as? SettingsActivity)?.fileManager?.let { fileManager ->
                fileManager.createNewFile(
                    mimeType = "application/octet-stream",
                    title = if (passphrase.isEmpty()) "terminal-backup.tar.gz" else "terminal-backup.tar.gz.enc",
                ) { fileObject ->
                    DefaultScope.launch(Dispatchers.IO) {
                        if (fileObject == null) return@launch
                        val targetFile = File.createTempFile("terminal-backup-", ".tar.gz", getTempDir())
                        val encryptedFile = File.createTempFile("terminal-backup-", ".tar.gz.enc", getTempDir())
                        val loading = LoadingPopup(activity, null)
                        withContext(Dispatchers.Main) { runCatching { loading.show() } }

                        try {
                            val ok = TerminalBackup.create(targetFile)
                            val toCopy =
                                if (!ok) {
                                    null
                                } else if (passphrase.isEmpty()) {
                                    targetFile
                                } else {
                                    runCatching {
                                        BackupCrypto.encrypt(targetFile, encryptedFile, passphrase)
                                        encryptedFile
                                    }
                                        .onFailure { it.printStackTrace() }
                                        .getOrNull()
                                }
                            var copied = false
                            if (toCopy != null) {
                                copied =
                                    runCatching {
                                        toCopy.inputStream().use { input ->
                                            fileObject.getOutputStream(false).use { output -> input.copyTo(output) }
                                        }
                                    }
                                        .onFailure { it.printStackTrace() }
                                        .isSuccess
                            }

                            withContext(Dispatchers.Main + NonCancellable) {
                                runCatching { loading.hide() }
                                when {
                                    copied -> toast(strings.success)
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
                            withContext(NonCancellable) {
                                targetFile.delete()
                                encryptedFile.delete()
                            }
                        }
                    }
                }
            }
        }

        PreferenceGroup(heading = stringResource(strings.user_data)) {
            val restore =
                rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri == null) {
                        return@rememberLauncherForActivityResult
                    }
                    val activeSessions = Terminal.instance?.sessionBinder?.get()?.getService()?.hasActiveWork() == true
                    if (activeSessions) {
                        toast(strings.restore_sessions_active)
                        return@rememberLauncherForActivityResult
                    }

                    val loading = LoadingPopup(activity, null)
                    loading.show()

                    DefaultScope.launch(Dispatchers.IO) {
                        val fileObject = uri.toFileObject(expectedIsFile = true)

                        val tempFile = File.createTempFile("terminal-backup-", ".tar.gz", getTempDir())
                        var handedOff = false

                        try {
                            fileObject.getInputStream().use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream -> inputStream.copyTo(outputStream) }
                            }

                            if (BackupCrypto.isEncrypted(tempFile)) {
                                handedOff = true
                                withContext(Dispatchers.Main) {
                                    runCatching { loading.hide() }
                                    pendingRestoreFile = tempFile
                                    restorePassphrase = ""
                                }
                                return@launch
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
                            if (!handedOff) withContext(NonCancellable) { tempFile.delete() }
                        }
                    }
                }

            SettingsItem(
                label = stringResource(strings.backup),
                description = stringResource(strings.terminal_backup),
                showSwitch = false,
                default = false,
                sideEffect = { exportPassphrase = "" },
            )

            SettingsItem(
                label = stringResource(strings.restore),
                description = stringResource(strings.restore_terminal),
                showSwitch = false,
                default = false,
                sideEffect = { restore.launch("*/*") },
            )

            SettingsItem(
                label = stringResource(strings.auto_backup),
                description = stringResource(strings.auto_backup_desc),
                default = Settings.auto_backup,
                sideEffect = {
                    Settings.auto_backup = it
                    if (it) {
                        AutoBackupJobService.schedule(context)
                    } else {
                        AutoBackupJobService.cancel(context)
                    }
                },
            )

            SettingsItem(
                label = stringResource(strings.auto_backup_encrypt),
                description = stringResource(strings.auto_backup_encrypt_desc),
                showSwitch = false,
                default = false,
                sideEffect = {
                    autoBackupPhrase =
                        when (val state = AutoBackupPassphrase.getState()) {
                            is PassphraseState.Ready -> state.phrase
                            else -> ""
                        }
                    showAutoBackupPhrase = true
                },
            )

            NextScreenCard(
                label = stringResource(strings.manage_backups),
                description = stringResource(strings.manage_backups_desc),
                navController = navController,
                route = SettingsRoutes.TerminalBackups,
            )

            NextScreenCard(
                label = stringResource(strings.custom_binds),
                description = stringResource(strings.custom_binds_desc),
                navController = navController,
                route = SettingsRoutes.TerminalBinds,
            )

            SettingsItem(
                label = stringResource(strings.uninstall),
                default = false,
                description = stringResource(strings.uninstall_terminal),
                showSwitch = false,
                sideEffect = {
                    dialogRes(
                        activity = activity,
                        title = strings.attention.getString(),
                        msg = strings.uninstall_terminal_warning.getString(),
                        onCancel = {},
                        okRes = strings.delete,
                        onOk = {
                            DefaultScope.launch(Dispatchers.IO) {
                                val loading = LoadingPopup(activity, null)
                                withContext(Dispatchers.Main) { runCatching { loading.show() } }
                                runCatching {
                                    localBinDir().deleteRecursively()
                                    localLibDir().deleteRecursively()
                                    sandboxDir().deleteRecursively()
                                    localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").delete()
                                }
                                resetTerminalFiles()
                                Terminal.instance?.sessionBinder?.get()?.getService()?.clearSavedSessions()
                                terminalInstalled = false
                                withContext(Dispatchers.Main + NonCancellable) {
                                    runCatching { loading.hide() }
                                    toast(strings.success)
                                }
                            }
                        },
                    )
                },
            )
        }

        if (exportPassphrase != null) {
            SingleInputDialog(
                title = stringResource(strings.backup_encrypt),
                inputLabel = stringResource(strings.backup_passphrase),
                inputValue = exportPassphrase.orEmpty(),
                onInputValueChange = { exportPassphrase = it },
                confirmEnabled = exportPassphrase?.isNotEmpty() == true,
                isPassword = true,
                onConfirm = {
                    val phrase = exportPassphrase.orEmpty()
                    exportPassphrase = null
                    launchExport(phrase)
                },
                onDismiss = { exportPassphrase = null },
            )
        }

        val encryptedRestoreFile = pendingRestoreFile
        if (encryptedRestoreFile != null) {
            SingleInputDialog(
                title = stringResource(strings.backup_unlock),
                inputLabel = stringResource(strings.backup_passphrase),
                inputValue = restorePassphrase,
                onInputValueChange = { restorePassphrase = it },
                confirmEnabled = restorePassphrase.isNotEmpty(),
                isPassword = true,
                onConfirm = {
                    pendingRestoreFile = null
                    val phrase = restorePassphrase
                    restorePassphrase = ""
                    DefaultScope.launch(Dispatchers.IO) {
                        val loading = LoadingPopup(activity, null)
                        withContext(Dispatchers.Main) { runCatching { loading.show() } }
                        val plain = File.createTempFile("terminal-backup-decrypted-", ".tar.gz", getTempDir())
                        val error = runCatching {
                            BackupCrypto.decrypt(encryptedRestoreFile, plain, phrase)
                            TerminalBackup.restore(plain)
                        }
                            .getOrElse { it.message ?: "restore failed" }
                        withContext(Dispatchers.Main + NonCancellable) {
                            runCatching { loading.hide() }
                            if (error == null) {
                                terminalInstalled = isTerminalInstalled()
                                toast(strings.restart_required)
                            } else {
                                toast(strings.setup_failed.getFilledString(error))
                            }
                        }
                        withContext(NonCancellable) {
                            encryptedRestoreFile.delete()
                            plain.delete()
                        }
                    }
                },
                onDismiss = {
                    encryptedRestoreFile.delete()
                    pendingRestoreFile = null
                    restorePassphrase = ""
                },
            )
        }

        if (showAutoBackupPhrase) {
            SingleInputDialog(
                title = stringResource(strings.auto_backup_encrypt),
                inputLabel = stringResource(strings.auto_backup_encrypt_desc),
                inputValue = autoBackupPhrase,
                onInputValueChange = { autoBackupPhrase = it },
                isPassword = true,
                onConfirm = {
                    if (autoBackupPhrase.isBlank()) {
                        AutoBackupPassphrase.clear()
                    } else {
                        AutoBackupPassphrase.save(autoBackupPhrase)
                    }
                    autoBackupPhrase = ""
                    showAutoBackupPhrase = false
                    toast(strings.success)
                },
                onDismiss = {
                    autoBackupPhrase = ""
                    showAutoBackupPhrase = false
                },
            )
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun TerminalInputSettings(navController: NavController) {
    PreferenceLayout(label = stringResource(strings.input), backArrowVisible = true) {
        val context = LocalContext.current
        val activity = LocalActivity.current as? AppCompatActivity
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
                navController = navController,
                route = SettingsRoutes.TerminalExtraKeys,
            )

            NextScreenCard(
                label = stringResource(strings.manage_snippets),
                description = stringResource(strings.manage_snippets_desc),
                navController = navController,
                route = SettingsRoutes.TerminalSnippets,
            )

            SettingsItem(
                label = stringResource(strings.clipboard_keybindings),
                description = stringResource(strings.clipboard_keybindings_desc),
                default = Settings.terminal_clipboard_keybindings,
                sideEffect = { Settings.terminal_clipboard_keybindings = it },
            )

            SettingsItem(
                label = stringResource(strings.gamepad_input),
                description = stringResource(strings.gamepad_input_desc),
                default = Settings.gamepad_input,
                sideEffect = { Settings.gamepad_input = it },
            )

            RoundedValueSlider(
                label = stringResource(strings.scrollback_buffer),
                description = stringResource(strings.scrollback_buffer_desc),
                min = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN,
                max = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX,
                default = Settings.terminal_scrollback_buffer,
                stepSize = 5_000,
            ) {
                Settings.terminal_scrollback_buffer = it
                toast(strings.restart_required)
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
