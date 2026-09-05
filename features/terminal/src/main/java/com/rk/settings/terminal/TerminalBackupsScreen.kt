package com.rk.settings.terminal

import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.DefaultScope
import com.rk.components.InfoBlock
import com.rk.components.compose.preferences.base.PreferenceLayoutLazyColumn
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.terminal.TerminalBackup
import com.rk.utils.LoadingPopup
import com.rk.utils.dialogRes
import com.rk.utils.toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalBackupsScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current as? AppCompatActivity

    // Bumped after every create/delete/restore to re-list the backup directory.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var backups by remember { mutableStateOf(emptyList<File>()) }
    LaunchedEffect(refreshTrigger) {
        backups =
            withContext(Dispatchers.IO) {
                TerminalBackup.backupDir()
                    .listFiles { f -> f.name.startsWith("terminal-backup-") && f.name.endsWith(".tar.gz") }
                    ?.sortedByDescending { it.name }
                    .orEmpty()
            }
    }

    PreferenceLayoutLazyColumn(label = stringResource(strings.manage_backups), backArrowVisible = true) {
        item {
            // Horizontal only: the lazy layout already spaces items 8dp apart.
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val loading = LoadingPopup(activity, null)
                        loading.show()
                        DefaultScope.launch(Dispatchers.IO) {
                            val ok = runCatching {
                                TerminalBackup.autoBackup()
                            }
                                .getOrElse {
                                    it.printStackTrace()
                                    false
                                }
                            withContext(Dispatchers.Main + NonCancellable) {
                                runCatching { loading.hide() }
                                if (ok) {
                                    toast(strings.success)
                                } else {
                                    toast(strings.failed)
                                }
                            }
                            refreshTrigger++
                        }
                    },
                ) {
                    Text(stringResource(strings.backup_now))
                }
            }
        }

        item { InfoBlock(text = stringResource(strings.restore_terminal)) }

        if (backups.isEmpty()) {
            item {
                Text(
                    text = stringResource(strings.no_backups_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        items(backups, key = { it.absolutePath }) { backup ->
            BackupItem(
                backup = backup,
                onRestore = {
                    dialogRes(
                        activity = activity,
                        title = strings.restore.getString(),
                        msg = backup.name,
                        okRes = strings.restore,
                        onCancel = {},
                        onOk = {
                            val loading = LoadingPopup(activity, null)
                            loading.show()
                            DefaultScope.launch(Dispatchers.IO) {
                                val error = runCatching {
                                    TerminalBackup.restore(backup)
                                }
                                    .getOrElse { it.message ?: "restore failed" }
                                withContext(Dispatchers.Main + NonCancellable) {
                                    runCatching { loading.hide() }
                                    if (error == null) {
                                        // Running sessions keep the old rootfs mapped.
                                        toast(strings.restart_required)
                                    } else {
                                        toast(strings.setup_failed.getFilledString(error))
                                    }
                                }
                                refreshTrigger++
                            }
                        },
                    )
                },
                onDelete = {
                    DefaultScope.launch(Dispatchers.IO) {
                        val ok = runCatching { backup.delete() }.getOrDefault(false)
                        withContext(Dispatchers.Main + NonCancellable) {
                            toast(if (ok) strings.success else strings.failed)
                        }
                        refreshTrigger++
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupItem(backup: File, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val details =
        remember(backup.absolutePath) {
            Formatter.formatShortFileSize(context, backup.length()) +
                " · " +
                DateFormat.getDateTimeInstance().format(Date(backup.lastModified()))
        }

    // Tap anywhere on the entry to restore; trash icon deletes.
    PreferenceTemplate(
        modifier = Modifier.clickable(onClick = onRestore),
        verticalPadding = 10.dp,
        title = { Text(text = backup.name) },
        description = { Text(text = details) },
        endWidget = {
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Outlined.Delete, contentDescription = stringResource(strings.delete))
            }
        },
    )
}
