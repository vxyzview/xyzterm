package com.rk.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.rk.settings.Settings

/**
 * One-tap command chips rendered between the terminal output and the extra-keys
 * row. Tapping a chip writes the command plus a newline into the active session.
 */
@Composable
fun SnippetsRow(port: TerminalViewPort) {
    val snippets = remember(Settings.terminal_snippets) { SnippetStore.decode(Settings.terminal_snippets) }
    if (snippets.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        snippets.forEach { snippet ->
            SuggestionChip(
                onClick = {
                    val session = port.view()?.mTermSession ?: return@SuggestionChip
                    session.write(snippet.command + "\n")
                    // Jump back to the live line so the effect is visible.
                    port.view()?.setTopRow(0)
                },
                label = { Text(snippet.label, maxLines = 1) },
                colors =
                    SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        }
    }
}
