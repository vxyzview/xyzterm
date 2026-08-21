package com.rk.settings.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rk.components.ResetButton
import com.rk.components.compose.preferences.base.LocalIsExpandedScreen
import com.rk.components.compose.preferences.base.PreferenceScaffold
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.Settings
import com.rk.theme.Typography
import com.rk.utils.openUrl

const val DEFAULT_TERMINAL_EXTRA_KEYS =
    ("[" +
        "\n  [" +
        "\n    \"ESC\"," +
        "\n    {" +
        "\n      \"key\": \"/\"," +
        "\n      \"popup\": \"\\\\\"" +
        "\n    }," +
        "\n    {" +
        "\n      \"key\": \"-\"," +
        "\n      \"popup\": \"|\"" +
        "\n    }," +
        "\n    \"HOME\"," +
        "\n    \"UP\"," +
        "\n    \"END\"," +
        "\n    \"PGUP\"" +
        "\n  ]," +
        "\n  [" +
        "\n    \"TAB\"," +
        "\n    \"CTRL\"," +
        "\n    \"ALT\"," +
        "\n    \"LEFT\"," +
        "\n    \"DOWN\"," +
        "\n    \"RIGHT\"," +
        "\n    \"PGDN\"" +
        "\n  ]" +
        "\n]")

@Composable
fun TerminalExtraKeys() {
    val context = LocalContext.current

    var text by remember { mutableStateOf(Settings.terminal_extra_keys) }

    // Live validation: surface a broken matrix while typing instead of only
    // showing the fallback toast after navigating back to the terminal.
    val jsonError = remember(text) { runCatching { org.json.JSONArray(text) }.exceptionOrNull() }

    fun save() {
        Settings.terminal_extra_keys = text
    }

    PreferenceScaffold(
        label = stringResource(strings.change_extra_keys),
        isExpandedScreen = LocalIsExpandedScreen.current,
        actions = {
            ResetButton {
                text = DEFAULT_TERMINAL_EXTRA_KEYS
                Preference.removeKey("terminal_extra_keys")
                save()
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(strings.see_termux_extra_keys),
                    fontSize = Typography.bodyMedium.fontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        context.openUrl("https://wiki.termux.com/wiki/Touch_Keyboard#Extra_Keys_Row")
                    }
                ) {
                    Icon(
                        painter = painterResource(drawables.open_in_new),
                        contentDescription = stringResource(strings.open),
                    )
                }
            }
            HorizontalDivider()

            TextField(
                value = text,
                onValueChange = {
                    text = it
                    Settings.terminal_extra_keys = it
                },
                isError = jsonError != null,
                supportingText = {
                    if (jsonError != null) {
                        Text(stringResource(strings.invalid_terminal_extra_keys))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                textStyle =
                    TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    ),
            )
        }
    }
}
