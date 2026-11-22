package com.xyzterm.settings

import com.xyzterm.activities.settings.SettingsRoutes
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings

data class SettingsEntry(val label: String, val description: String, val route: String)

/** Pure filter: settings search never touches the filesystem or preference store. */
fun filterSettings(entries: List<SettingsEntry>, query: String): List<SettingsEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    return entries.filter {
        it.label.contains(q, ignoreCase = true) ||
            it.description.contains(q, ignoreCase = true) ||
            it.route.contains(q, ignoreCase = true)
    }
}

/** All destinations, including feature-registered ones, as searchable entries. */
fun settingsEntries(): List<SettingsEntry> = buildList {
    add(SettingsEntry(strings.app.getString(), strings.app_desc.getString(), SettingsRoutes.AppSettings.route))
    add(SettingsEntry(strings.themes.getString(), strings.theme_settings.getString(), SettingsRoutes.Themes.route))
    add(
        SettingsEntry(
            strings.keybindings.getString(),
            strings.keybindings_desc.getString(),
            SettingsRoutes.Keybindings.route,
        ),
    )
    SettingsRegistry.categories.forEach {
        add(
            SettingsEntry(
                it.labelRes.getString(),
                it.descriptionRes.getString(),
                it.route,
            ),
        )
    }
    add(SettingsEntry(strings.advanced.getString(), "", SettingsRoutes.TerminalAdvanced.route))
    add(
        SettingsEntry(
            strings.desktop.getString(),
            strings.desktop_desc.getString(),
            SettingsRoutes.TerminalDesktop.route,
        ),
    )
    add(SettingsEntry(strings.appearance.getString(), "", SettingsRoutes.TerminalAppearance.route))
    add(SettingsEntry(strings.input.getString(), "", SettingsRoutes.TerminalInput.route))
    add(SettingsEntry(strings.user_data.getString(), "", SettingsRoutes.TerminalData.route))
    add(
        SettingsEntry(
            strings.change_extra_keys.getString(),
            strings.change_extra_keys_desc.getString(),
            SettingsRoutes.TerminalExtraKeys.route,
        ),
    )
    add(
        SettingsEntry(
            strings.manage_snippets.getString(),
            strings.manage_snippets_desc.getString(),
            SettingsRoutes.TerminalSnippets.route,
        ),
    )
    add(
        SettingsEntry(
            strings.terminal_health.getString(),
            strings.terminal_health_desc.getString(),
            SettingsRoutes.TerminalCheck.route,
        ),
    )
    add(
        SettingsEntry(
            strings.manage_terminal_font.getString(),
            strings.manage_terminal_font_desc.getString(),
            SettingsRoutes.TerminalFontScreen.route,
        ),
    )
    add(
        SettingsEntry(
            strings.manage_backups.getString(),
            strings.manage_backups_desc.getString(),
            SettingsRoutes.TerminalBackups.route,
        ),
    )
    add(
        SettingsEntry(
            strings.custom_binds.getString(),
            strings.custom_binds_desc.getString(),
            SettingsRoutes.TerminalBinds.route,
        ),
    )
    add(SettingsEntry(strings.about.getString(), strings.about_desc.getString(), SettingsRoutes.About.route))
    add(SettingsEntry(strings.support.getString(), strings.support_desc.getString(), SettingsRoutes.Support.route))
    add(SettingsEntry(strings.lang.getString(), "", SettingsRoutes.LanguageScreen.route))
    add(
        SettingsEntry(
            strings.debug_options.getString(),
            strings.debug_options_desc.getFilledString(strings.app_name.getString()),
            SettingsRoutes.DeveloperOptions.route,
        ),
    )
}
