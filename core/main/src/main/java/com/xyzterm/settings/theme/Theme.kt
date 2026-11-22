package com.xyzterm.settings.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xyzterm.DefaultScope
import com.xyzterm.components.BottomSheetContent
import com.xyzterm.components.SettingsItem
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.components.compose.preferences.base.PreferenceTemplate
import com.xyzterm.events.AppEvent
import com.xyzterm.events.Events
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.theme.DYNAMIC_THEME_ID
import com.xyzterm.theme.builtInThemes
import com.xyzterm.theme.currentTheme
import com.xyzterm.theme.lime
import kotlinx.coroutines.launch

@Composable
fun ThemeScreen(navController: NavController, modifier: Modifier = Modifier) {
    val showDayNightBottomSheet = remember { mutableStateOf(false) }
    val amoledState = remember { mutableStateOf(Settings.amoled) }

    PreferenceLayout(label = stringResource(strings.themes)) {
        PreferenceGroup(heading = stringResource(strings.theme_settings)) {
            SettingsItem(
                label = stringResource(id = strings.theme_mode),
                description = stringResource(id = strings.theme_mode_desc),
                showSwitch = false,
                default = false,
                sideEffect = { showDayNightBottomSheet.value = true },
            )

            SettingsItem(
                label = stringResource(id = strings.oled),
                description = stringResource(id = strings.oled_desc),
                default = Settings.amoled,
                state = amoledState,
                sideEffect = { Settings.amoled = it },
            )
        }

        PreferenceGroup(heading = stringResource(strings.themes)) {
            // Dynamic (Material You) is always available and is the default; no on/off toggle.
            SettingsItem(
                label = stringResource(id = strings.dynamic_colors),
                description = stringResource(id = strings.dynamic_colors_desc),
                showSwitch = false,
                default = false,
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 16.dp),
                        selected = Settings.theme == DYNAMIC_THEME_ID,
                        onClick = null,
                    )
                },
                sideEffect = {
                    val oldTheme = currentTheme.value
                    Settings.theme = DYNAMIC_THEME_ID
                    DefaultScope.launch { Events.publish(AppEvent.ThemeChanged(lime, oldTheme)) }
                },
            )

            builtInThemes.forEach { theme ->
                SettingsItem(
                    label = theme.name,
                    description = null,
                    showSwitch = false,
                    default = false,
                    startWidget = {
                        RadioButton(
                            modifier = Modifier.padding(start = 16.dp),
                            selected = currentTheme.value.id == theme.id,
                            onClick = null,
                        )
                    },
                    sideEffect = {
                        val oldTheme = currentTheme.value
                        Settings.theme = theme.id
                        DefaultScope.launch { Events.publish(AppEvent.ThemeChanged(theme, oldTheme)) }
                    },
                )
            }
        }

        if (showDayNightBottomSheet.value) {
            DayNightDialog(showBottomSheet = showDayNightBottomSheet, context = LocalContext.current)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayNightDialog(showBottomSheet: MutableState<Boolean>, context: Context) {
    val bottomSheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    var selectedMode by remember { mutableIntStateOf(Settings.theme_mode) }

    val modes =
        listOf(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )
    val modeLabels =
        listOf(
            context.getString(strings.light_mode),
            context.getString(strings.dark_mode),
            context.getString(strings.auto_mode),
        )

    if (showBottomSheet.value) {
        ModalBottomSheet(onDismissRequest = { showBottomSheet.value = false }, sheetState = bottomSheetState) {
            BottomSheetContent(
                title = { Text(text = stringResource(id = strings.theme_mode)) },
                buttons = {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                bottomSheetState.hide()
                                showBottomSheet.value = false
                            }
                        },
                    ) {
                        Text(text = stringResource(id = strings.cancel))
                    }
                },
            ) {
                LazyColumn {
                    itemsIndexed(modes, key = { _, mode -> mode }) { index, mode ->
                        PreferenceTemplate(
                            title = { Text(text = modeLabels[index]) },
                            modifier =
                                Modifier.clickable {
                                    selectedMode = mode
                                    Settings.theme_mode = selectedMode
                                    AppCompatDelegate.setDefaultNightMode(selectedMode)
                                    coroutineScope.launch {
                                        bottomSheetState.hide()
                                        showBottomSheet.value = false
                                    }
                                },
                            startWidget = { RadioButton(selected = selectedMode == mode, onClick = null) },
                            carded = false,
                        )
                    }
                }
            }
        }
    }
}
