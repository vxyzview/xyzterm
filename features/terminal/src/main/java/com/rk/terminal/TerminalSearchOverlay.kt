package com.rk.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

/**
 * Inline search over the session's scrollback. Matches are case-insensitive;
 * the arrows walk through hits and the view scrolls so the hit line is visible.
 */
@Composable
fun TerminalSearchOverlay(onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val scan = remember(query) { query.takeIf(String::isNotBlank)?.let(::ScrollbackScan) }
    var selectedHit by remember(query) { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(scan) {
        val active = scan ?: return@LaunchedEffect
        var waitedForEmulator = 0L
        while (!active.done) {
            val emulator = terminalView.get()?.mEmulator
            if (emulator == null) {
                if (waitedForEmulator >= EMULATOR_WAIT_MS) return@LaunchedEffect
                delay(SCAN_SLICE_MS)
                waitedForEmulator += SCAN_SLICE_MS
                continue
            }
            runCatching { active.advance(emulator) }
            // Jump to the first hit as soon as the scan finds one, so the
            // counter reads "1 of N" instead of "0 of N" while scanning.
            if (selectedHit == -1 && active.hits.isNotEmpty()) {
                selectedHit = 0
                terminalView.get()?.let { active.scrollToRow(it, 0) }
            }
            if (!active.done) delay(SCAN_SLICE_MS)
        }
    }

    fun stepSelection(delta: Int) {
        val active = scan ?: return
        if (active.hits.isEmpty()) return
        selectedHit = ((selectedHit + delta) % active.hits.size + active.hits.size) % active.hits.size
        active.scrollToRow(terminalView.get() ?: return, selectedHit)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(strings.search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { stepSelection(1) }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )

            if (scan != null) {
                Text(
                    text =
                        when {
                            scan.done && scan.hits.isEmpty() -> stringResource(strings.search_no_results)
                            else ->
                                stringResource(
                                    strings.search_matches,
                                    if (selectedHit in scan.hits.indices) selectedHit + 1 else 0,
                                    scan.hits.size,
                                )
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            IconButton(onClick = { stepSelection(-1) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(strings.previous),
                )
            }
            IconButton(onClick = { stepSelection(1) }) {
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

private class ScrollbackScan(val query: String) {
    var done = false
        private set
    val hits = mutableListOf<Int>()

    private var lowest = 0
    private var totalRows = 0
    private var nextIndex = 0

    fun advance(emulator: TerminalEmulator) {
        if (done) return
        val screen = emulator.screen
        if (totalRows == 0) {
            lowest = -screen.activeTranscriptRows
            totalRows = emulator.mRows - lowest
            if (totalRows <= 0) {
                done = true
                return
            }
        }

        val end = minOf(nextIndex + SCAN_CHUNK_ROWS, totalRows)
        for (index in nextIndex until end) {
            val y = lowest + index
            val line =
                runCatching { screen.getSelectedText(0, y, emulator.mColumns - 1, y, false, false) }
                    .getOrNull()
            if (line != null && line.contains(query, ignoreCase = true)) {
                hits.add(index)
            }
        }
        nextIndex = end
        if (nextIndex >= totalRows) done = true
    }

    fun scrollToRow(view: TerminalView, position: Int): Boolean {
        val index = hits.getOrNull(position) ?: return false
        view.setTopRow(lowest + index)
        view.invalidate()
        return true
    }
}

private const val SCAN_CHUNK_ROWS = 256

private const val SCAN_SLICE_MS = 32L

private const val EMULATOR_WAIT_MS = 2000L
