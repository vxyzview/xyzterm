package com.rk.settings.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.components.InfoBlock
import com.rk.components.compose.preferences.base.PreferenceLayoutLazyColumn
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.exec.Binding
import com.rk.exec.UserBindings
import com.rk.resources.strings
import com.rk.settings.Settings

@Composable
fun UserBindingsScreen() {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val bindings = remember(refreshTrigger) { UserBindings.decode(Settings.custom_bindings) }

    var showAddDialog by remember { mutableStateOf(false) }

    fun persist(list: List<Binding>) {
        Settings.custom_bindings = UserBindings.encode(list)
        refreshTrigger++
    }

    PreferenceLayoutLazyColumn(
        label = stringResource(strings.custom_binds),
        backArrowVisible = true,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                text = { Text(stringResource(strings.bind_add)) },
                icon = { Icon(imageVector = Icons.Outlined.Add, contentDescription = null) },
            )
        },
    ) {
        item { InfoBlock(text = stringResource(strings.bind_warning), warning = true) }

        items(bindings, key = { it.outside + it.inside.orEmpty() }) { binding ->
            PreferenceTemplate(
                verticalPadding = 16.dp,
                title = {
                    Text(text = binding.outside, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                description = {
                    Text(
                        text = binding.inside ?: binding.outside,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                endWidget = {
                    IconButton(onClick = { persist(bindings.toMutableList().apply { remove(binding) }) }) {
                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = stringResource(strings.delete))
                    }
                },
            )
        }

        if (bindings.isEmpty()) {
            item {
                Text(
                    text = stringResource(strings.custom_binds_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showAddDialog) {
        BindEditDialog(
            existing = bindings.map { it.outside }.toSet(),
            onDismiss = { showAddDialog = false },
            onSave = { outside, inside ->
                persist(bindings + Binding(outside, inside))
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun BindEditDialog(existing: Set<String>, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var hostPath by remember { mutableStateOf("") }
    var guestPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(strings.bind_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostPath,
                    onValueChange = {
                        hostPath = it
                        error = null
                    },
                    label = { Text(stringResource(strings.bind_host_path)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPath,
                    onValueChange = {
                        guestPath = it
                        error = null
                    },
                    label = { Text(stringResource(strings.bind_guest_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = stringResource(error!!),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hostPath.isNotBlank(),
                onClick = {
                    val outside = hostPath.trim()
                    val inside = guestPath.trim().ifEmpty { null }

                    error =
                        when {
                            !UserBindings.isValidHostPath(outside) -> strings.bind_invalid_host
                            inside != null && !UserBindings.isValidGuestPath(inside) ->
                                strings.bind_invalid_guest
                            existing.contains(outside) -> strings.bind_duplicate
                            else -> null
                        }
                    if (error == null) onSave(outside, inside)
                },
            ) {
                Text(stringResource(strings.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(strings.cancel)) } },
    )
}
