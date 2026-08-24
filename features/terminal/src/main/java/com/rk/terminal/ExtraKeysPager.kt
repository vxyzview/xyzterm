package com.rk.terminal

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.settings.DEFAULT_TERMINAL_EXTRA_KEYS
import com.rk.terminal.virtualkeys.VirtualKeysConstants
import com.rk.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.virtualkeys.VirtualKeysView
import com.rk.utils.toast
import java.lang.ref.WeakReference

/**
 * The two-page input area under the terminal: page 0 is the extra-keys row,
 * page 1 a quick text-input field. Swipe between them.
 */
@Composable
fun ExtraKeysPager(onSurfaceColor: Int) {
    // Height derives from the key-row count so each key keeps a >=48dp touch
    // target (a11y minimum). The default matrix has 2 rows -> 96dp; landscape
    // also 96dp so keys are tappable instead of the previous 26-37dp. The
    // input page uses the same height so the two pager pages align.
    //
    // Parse the extra-keys matrix once per settings change, not on every
    // recomposition (the JSONArray constructor is slow enough to jank the
    // terminal screen when sessions/theme/state churn).
    val extraKeysRowCount =
        remember(Settings.terminal_extra_keys) {
            runCatching { org.json.JSONArray(Settings.terminal_extra_keys).length() }.getOrElse { 2 }
        }
    // Hidden entirely when disabled or the matrix is empty — an empty row
    // would still reserve space and a swipe zone.
    val showExtraKeys = Settings.terminal_show_extra_keys && extraKeysRowCount > 0
    if (!showExtraKeys) return

    val pagerState = rememberPagerState(pageCount = { 2 })
    val keyRowHeight = (extraKeysRowCount * 48).coerceAtLeast(52).dp
    val extraKeysLabel = stringResource(strings.extra_keys)

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(keyRowHeight),
    ) { page ->
        when (page) {
            0 -> {
                // Tracks the last key matrix applied to the view so the update
                // block only reloads when the setting actually changed.
                var appliedExtraKeys by remember { mutableStateOf(Settings.terminal_extra_keys) }
                AndroidView(
                    factory = { context ->
                        VirtualKeysView(context, null).apply {
                            virtualKeysView = WeakReference(this)
                            virtualKeysViewClient =
                                terminalView.get()?.mTermSession?.let { VirtualKeysListener(it) }

                            buttonTextColor = onSurfaceColor

                            runCatching {
                                reload(
                                    VirtualKeysInfo(
                                        Settings.terminal_extra_keys,
                                        "",
                                        VirtualKeysConstants.CONTROL_CHARS_ALIASES,
                                    )
                                )
                            }
                                .onFailure {
                                    toast(strings.invalid_terminal_extra_keys)
                                    reload(
                                        VirtualKeysInfo(
                                            DEFAULT_TERMINAL_EXTRA_KEYS,
                                            "",
                                            VirtualKeysConstants.CONTROL_CHARS_ALIASES,
                                        )
                                    )
                                }
                        }
                    },
                    update = { view ->
                        val current = Settings.terminal_extra_keys
                        if (current != appliedExtraKeys) {
                            appliedExtraKeys = current
                            runCatching {
                                view.reload(
                                    VirtualKeysInfo(current, "", VirtualKeysConstants.CONTROL_CHARS_ALIASES)
                                )
                            }
                                .onFailure {
                                    toast(strings.invalid_terminal_extra_keys)
                                    view.reload(
                                        VirtualKeysInfo(
                                            DEFAULT_TERMINAL_EXTRA_KEYS,
                                            "",
                                            VirtualKeysConstants.CONTROL_CHARS_ALIASES,
                                        )
                                    )
                                }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(keyRowHeight)
                            .semantics { contentDescription = extraKeysLabel },
                )
            }

            1 -> {
                var text by rememberSaveable { mutableStateOf("") }
                val focusRequester = remember { FocusRequester() }

                Box(
                    modifier = Modifier.fillMaxWidth().height(keyRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        maxLines = 1,
                        singleLine = true,
                        label = { Text(text = stringResource(strings.input)) },
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (text.isEmpty()) {
                                        // Dispatch enter key events if text is empty
                                        val eventDown =
                                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                        val eventUp =
                                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                                        terminalView.get()?.dispatchKeyEvent(eventDown)
                                        terminalView.get()?.dispatchKeyEvent(eventUp)
                                    } else {
                                        terminalView.get()?.currentSession?.write(text)
                                        text = ""
                                    }
                                }
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .focusRequester(focusRequester),
                    )
                }

                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        }
    }

    // Refocus the terminal when swiping back from the input page so typing
    // resumes without an extra tap.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0) {
            terminalView.get()?.requestFocus()
        }
    }
}
