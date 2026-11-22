package com.xyzterm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xyzterm.activities.settings.SettingsRoutes
import com.xyzterm.components.BrandHeader
import com.xyzterm.components.EmptyState
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.components.compose.preferences.base.PreferenceTemplate
import com.xyzterm.components.compose.preferences.category.PreferenceCategory
import com.xyzterm.feature.FeatureRegistry
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings

@Composable
fun SettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }
    val entries = remember { settingsEntries() }

    PreferenceLayout(label = stringResource(id = strings.settings), backArrowVisible = true) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(strings.search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BrandHeader()
        Spacer(modifier = Modifier.height(8.dp))

        if (query.isBlank()) {
            Categories(navController)
        } else {
            val results = filterSettings(entries, query)
            if (results.isEmpty()) {
                EmptyState(
                    painter = rememberVectorPainter(Icons.Filled.Search),
                    text = stringResource(strings.no_results),
                    actionLabel = stringResource(strings.clear_search),
                    onAction = { query = "" },
                )
            } else {
                PreferenceGroup {
                    results.forEach { entry ->
                        PreferenceTemplate(
                            modifier =
                                Modifier.semantics { role = Role.Button }
                                    .clickable { navController.navigate(entry.route) { launchSingleTop = true } },
                            verticalPadding = 14.dp,
                            title = { Text(entry.label) },
                            description = { Text(entry.description) },
                            endWidget = { Chevron() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Categories(navController: NavController) {
    PreferenceGroup {
        PreferenceCategory(
            label = stringResource(id = strings.app),
            description = stringResource(id = strings.app_desc),
            iconResource = drawables.android,
            endWidget = { Chevron() },
            onNavigate = { navController.navigate(SettingsRoutes.AppSettings.route) { launchSingleTop = true } },
        )

        PreferenceCategory(
            label = stringResource(strings.themes),
            description = stringResource(strings.theme_settings),
            iconResource = drawables.palette,
            endWidget = { Chevron() },
            onNavigate = { navController.navigate(SettingsRoutes.Themes.route) { launchSingleTop = true } },
        )

        PreferenceCategory(
            label = stringResource(strings.keybindings),
            description = stringResource(strings.keybindings_desc),
            iconResource = drawables.keyboard,
            endWidget = { Chevron() },
            onNavigate = { navController.navigate(SettingsRoutes.Keybindings.route) { launchSingleTop = true } },
        )

        SettingsRegistry.categories.forEach { category ->
            PreferenceCategory(
                label = stringResource(id = category.labelRes),
                description = stringResource(id = category.descriptionRes),
                iconResource = category.iconRes,
                endWidget = { Chevron() },
                onNavigate = { navController.navigate(category.route) { launchSingleTop = true } },
            )
        }

        if (FeatureRegistry.isEnabled("debug_mode")) {
            PreferenceCategory(
                label = stringResource(strings.debug_options),
                description = strings.debug_options_desc.getFilledString(strings.app_name.getString()),
                iconResource = drawables.build,
                endWidget = { Chevron() },
                onNavigate = {
                    navController.navigate(SettingsRoutes.DeveloperOptions.route) { launchSingleTop = true }
                },
            )
        }
    }

    PreferenceGroup {
        PreferenceTemplate(
            modifier =
                Modifier.semantics { role = Role.Button }
                    .clickable { navController.navigate(SettingsRoutes.About.route) { launchSingleTop = true } },
            verticalPadding = 14.dp,
            title = { Text(stringResource(id = strings.about)) },
            description = { Text(stringResource(id = strings.about_desc)) },
            startWidget = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            endWidget = { Chevron() },
        )

        PreferenceTemplate(
            modifier =
                Modifier.semantics { role = Role.Button }
                    .clickable { navController.navigate(SettingsRoutes.Support.route) { launchSingleTop = true } },
            verticalPadding = 14.dp,
            title = { Text(text = stringResource(strings.support)) },
            description = { Text(stringResource(id = strings.support_desc)) },
            startWidget = { SupportIcon() },
            endWidget = { Chevron() },
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
fun SupportIcon() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = if (Settings.donated) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
