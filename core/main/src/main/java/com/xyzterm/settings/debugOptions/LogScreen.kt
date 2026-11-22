package com.xyzterm.settings.debugOptions

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.xyzterm.activities.settings.SettingsActivity
import com.xyzterm.components.compose.preferences.base.LocalIsExpandedScreen
import com.xyzterm.components.compose.preferences.base.PreferenceScaffold
import com.xyzterm.crashhandler.CrashHandler.logErrorOrExit
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.utils.copyToClipboard
import com.xyzterm.utils.dialogRes
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow

@Composable
fun LogScreen(
    logText: String,
    issueTitle: String,
    copyLabel: String,
    flow: Flow<String>? = null,
    toolbarButtons: @Composable RowScope.() -> Unit,
) {
    // Keyed on logText so a new filter level replaces the buffer instead of
    // the screen keeping the stale initial text forever.
    var logs by remember(logText) { mutableStateOf(logText) }

    // Logcat can emit hundreds of lines per second. Appending to the whole
    // String on every line causes O(n^2) copies and re-layout; batch publishes.
    LaunchedEffect(flow, logText) {
        val buffer = StringBuilder(logText)
        var lastEmit = 0L
        flow?.collect { newLine ->
            buffer.append(newLine).append('\n')
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmit >= 100L) {
                lastEmit = now
                logs = buffer.toString()
            }
        }
    }

    PreferenceScaffold(
        label = stringResource(strings.logs),
        isExpandedScreen = LocalIsExpandedScreen.current,
        actions = {
            TextButton(onClick = { copyToClipboard(copyLabel, logs, true) }) { Text(stringResource(strings.copy)) }

            TextButton(
                onClick = { runCatching { reportLogs(logs, issueTitle, copyLabel) }.onFailure { logErrorOrExit(it) } },
            ) {
                Text(stringResource(strings.report_issue))
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Surface {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        toolbarButtons()
                    }

                    HorizontalDivider()
                }
            }

            SelectionContainer {
                Text(
                    text = logs,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun reportLogs(logText: String, issueTitle: String, copyLabel: String) {
    val context = SettingsActivity.instance ?: return

    val encodedTitle = URLEncoder.encode(issueTitle, StandardCharsets.UTF_8.toString())
    val urlStart = "https://github.com/vxyzview/xyzterm/issues/new?title=$encodedTitle&body="
    val url = urlStart + URLEncoder.encode("```log \n${logText}\n ```", StandardCharsets.UTF_8.toString())
    if (url.length > 2048) {
        val trimmedUrl =
            urlStart + URLEncoder.encode("```log \nPaste the logs here\n ```", StandardCharsets.UTF_8.toString())
        dialogRes(
            activity = context,
            title = strings.logs_too_long.getString(),
            msg = strings.logs_too_long_desc.getString(),
            okRes = strings.continue_action,
            onOk = {
                copyToClipboard(copyLabel, logText, true)
                val browserIntent = Intent(Intent.ACTION_VIEW, trimmedUrl.toUri())
                context.startActivity(browserIntent)
            },
            cancelable = false,
        )
        return
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
    context.startActivity(browserIntent)
}
