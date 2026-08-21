package com.rk.settings.terminal

import androidx.compose.foundation.clickable
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
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.Snippet
import com.rk.terminal.SnippetStore

@Composable
fun SnippetsScreen() {
    val activity = LocalActivity.current as? AppCompatActivity

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val snippets = remember(refreshTrigger) { SnippetStore.decode(Settings.terminal_snippets) }

    // Index of the snippet being edited, or NO_ADDING sentinel values.
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun persist(list: List<Snippet>) {
        Settings.terminal_snippets = SnippetStore.encode(list)
        refreshTrigger++
    }

    PreferenceLayoutLazyColumn(
        label = stringResource(strings.manage_snippets),
        backArrowVisible = true,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                text = { Text(stringResource(strings.snippet_add)) },
                icon = { Icon(imageVector = Icons.Outlined.Add, contentDescription = null) },
            )
        },
    ) {
        item { InfoBlock(text = stringResource(strings.manage_snippets_desc)) }

        items(snippets, key = { it.label + it.command }) { snippet ->
            val index = snippets.indexOf(snippet)
            PreferenceTemplate(
                modifier = Modifier.clickable(onClick = { editIndex = index }),
                verticalPadding = 10.dp,
                title = {
                    Text(text = snippet.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                description = {
                    Text(
                        text = snippet.command,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                endWidget = {
                    IconButton(onClick = {
                        persist(snippets.toMutableList().apply { removeAt(index) })
                    }) {
                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = stringResource(strings.delete))
                    }
                },
            )
        }

        if (snippets.isEmpty()) {
            item {
                Text(
                    text = stringResource(strings.snippet_add),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    val editing = editIndex?.let { snippets.getOrNull(it) }
    if (editing != null || showAddDialog) {
        SnippetEditDialog(
            initial = editing,
            onDismiss = {
                editIndex = null
                showAddDialog = false
            },
            onSave = { label, command ->
                val list = snippets.toMutableList()
                if (editing != null) {
                    list[editIndex!!] = Snippet(label, command)
                } else {
                    list.add(Snippet(label, command))
                }
                persist(list)
                editIndex = null
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun SnippetEditDialog(initial: Snippet?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var label by remember(initial) { mutableStateOf(initial?.label.orEmpty()) }
    var command by remember(initial) { mutableStateOf(initial?.command.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) strings.snippet_add else strings.manage_snippets)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(strings.snippet_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(strings.snippet_command)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && command.isNotBlank(),
                onClick = { onSave(label.trim(), command.trim()) },
            ) {
                Text(stringResource(strings.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(strings.cancel)) } },
    )
}
