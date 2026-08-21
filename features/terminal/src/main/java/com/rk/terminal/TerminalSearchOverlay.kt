package com.rk.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.termux.view.TerminalView

/**
 * Inline search over the session's scrollback. Matches are case-insensitive;
 * the arrows walk through hits and the view scrolls so the hit line is visible.
 */
@Composable
fun TerminalSearchOverlay(onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(strings.search)) },
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = { jumpMatch(query, forward = false) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(strings.previous),
                )
            }
            IconButton(onClick = { jumpMatch(query, forward = true) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(strings.next),
                )
            }
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = stringResource(strings.close))
            }
        }
    }
}

private fun jumpMatch(query: String, forward: Boolean): Boolean {
    val view = terminalView.get() ?: return false
    val emulator = view.mEmulator ?: return false
    if (query.isBlank()) return false

    val screen = emulator.screen
    val lowest = -screen.activeTranscriptRows
    val highest = emulator.mRows - 1
    val size = highest - lowest + 1
    if (size <= 0) return false

    val currentIndex = (view.topRow - lowest).coerceIn(0, size - 1)
    for (step in 1..size) {
        val index =
            ((if (forward) currentIndex + step else currentIndex - step) % size + size) % size
        val y = lowest + index
        if (y == view.topRow) continue

        val line =
            runCatching { screen.getSelectedText(0, y, emulator.mColumns - 1, y, false, false) }
                .getOrNull() ?: continue
        if (line.contains(query, ignoreCase = true)) {
            view.setTopRow(y)
            view.invalidate()
            return true
        }
    }
    return false
}
