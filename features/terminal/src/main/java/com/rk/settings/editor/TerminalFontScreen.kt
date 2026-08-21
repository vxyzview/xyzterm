package com.rk.settings.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rk.components.InfoBlock
import com.rk.components.ResetButton
import com.rk.components.compose.preferences.base.LocalIsExpandedScreen
import com.rk.components.compose.preferences.base.PreferenceScaffold
import com.rk.file.child
import com.rk.file.sandboxDir
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.utils.DEFAULT_TERMINAL_FONT_PATH
import com.rk.utils.FontCache
import com.rk.utils.toast
import java.io.File
import java.io.FileOutputStream

@Composable
fun TerminalFontScreen() {
    val context = LocalContext.current
    val etcFontExists = sandboxDir().child("etc/font.ttf").exists()
    var fontPath by remember { mutableStateOf(Settings.terminal_font_path) }

    // Preview the effective font: the custom pick if set, else the bundled default.
    val previewTypeface =
        remember(fontPath) {
            if (fontPath.isNotEmpty()) {
                FontCache.getTypeface(context, fontPath, Settings.is_terminal_font_asset)
                    ?: FontCache.getTypeface(context, DEFAULT_TERMINAL_FONT_PATH, true)
            } else {
                FontCache.getTypeface(context, DEFAULT_TERMINAL_FONT_PATH, true)
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
            onResult = { uri: Uri? ->
                if (uri != null) {
                    runCatching {
                        var fileName = "terminal-font.ttf"

                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1) {
                                    fileName = cursor.getString(nameIndex)
                                }
                            }
                        }

                        val destinationFile = File(context.filesDir, "fonts/$fileName")
                        destinationFile.parentFile?.mkdirs()
                        if (destinationFile.exists().not()) {
                            destinationFile.createNewFile()
                        }
                        context.contentResolver.openInputStream(uri).use { inputStream ->
                            FileOutputStream(destinationFile).use { outputStream ->
                                inputStream?.copyTo(outputStream)
                            }
                        }

                        Settings.terminal_font_path = destinationFile.absolutePath
                        Settings.is_terminal_font_asset = false
                        fontPath = destinationFile.absolutePath
                    }.onFailure {
                        it.printStackTrace()
                        toast(strings.failed)
                    }
                }
            },
        )

    PreferenceScaffold(
        label = stringResource(strings.manage_terminal_font),
        isExpandedScreen = LocalIsExpandedScreen.current,
        actions = {
            // Only way back to the bundled default once a custom font is set.
            ResetButton {
                Settings.terminal_font_path = ""
                fontPath = ""
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (etcFontExists) {
                InfoBlock(
                    icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
                    text = stringResource(strings.terminal_font_warning),
                    warning = true,
                )
            }

            Text(
                text = stringResource(strings.font_preview),
                fontFamily = FontFamily(previewTypeface),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )

            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { filePickerLauncher.launch("font/*") }) {
                    Text(stringResource(strings.open))
                }
            }

            Text(
                text = fontPath.ifEmpty { DEFAULT_TERMINAL_FONT_PATH },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
