package com.rk.activities.settings

sealed class SettingsRoutes(val route: String) {
    data object Settings : SettingsRoutes("settings")

    data object AppSettings : SettingsRoutes("app_settings")

    data object EditorSettings : SettingsRoutes("editor_settings")

    data object Keybindings : SettingsRoutes("keybindings")

    data object TerminalSettings : SettingsRoutes("terminal_settings")

    data object TerminalExtraKeys : SettingsRoutes("terminal_extra_keys")

    data object TerminalSnippets : SettingsRoutes("terminal_snippets")

    data object TerminalCheck : SettingsRoutes("terminal_check")

    data object TerminalBackups : SettingsRoutes("terminal_backups")

    data object About : SettingsRoutes("about")

    data object EditorFontScreen : SettingsRoutes("editor_font_screen")

    data object AppFontScreen : SettingsRoutes("app_font_screen")

    data object TerminalFontScreen : SettingsRoutes("terminal_font_screen")

    data object DefaultEncoding : SettingsRoutes("default_encoding")

    data object DefaultLineEnding : SettingsRoutes("default_line_ending")

    data object ToolbarActions : SettingsRoutes("toolbar_actions")

    data object ExtraKeys : SettingsRoutes("extra_keys")

    data object ExcludeFiles : SettingsRoutes("exclude_files")

    data object DeveloperOptions : SettingsRoutes("developer_options")

    data object AppLogs : SettingsRoutes("app_logs")

    data object Support : SettingsRoutes("support")

    data object LanguageScreen : SettingsRoutes("language")

    data object HtmlRunner : SettingsRoutes("html_preview")

    data object Themes : SettingsRoutes("theme")
}
