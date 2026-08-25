package com.rk.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.rk.settings.Settings
import kotlinx.coroutines.delay

/**
 * One-tap command chips rendered between the terminal output and the extra-keys
 * row. Tapping a chip writes the command plus a newline into the active session.
 */
@Composable
fun SnippetsRow() {
    val snippets = remember(Settings.terminal_snippets) { SnippetStore.decode(Settings.terminal_snippets) }
    if (snippets.isEmpty()) return

    var sessionActive by remember { mutableStateOf(terminalView.get()?.mTermSession != null) }

    LaunchedEffect(Unit) {
        while (!sessionActive) {
            sessionActive = terminalView.get()?.mTermSession != null
            if (!sessionActive) delay(250)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        snippets.forEach { snippet ->
            SuggestionChip(
                onClick = {
                    val session = terminalView.get()?.mTermSession ?: return@SuggestionChip
                    session.write(snippet.command + "\n")
                    // Jump back to the live line so the effect is visible.
                    terminalView.get()?.setTopRow(0)
                },
                label = { Text(snippet.label, maxLines = 1) },
                enabled = sessionActive,
                colors =
                    SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
