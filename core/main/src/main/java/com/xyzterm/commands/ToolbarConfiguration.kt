package com.xyzterm.commands

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.xyzterm.settings.Settings

object ToolbarConfiguration {
    const val DEFAULT_EDITOR_TOOLBAR_COMMANDS =
        "editor.undo|editor.redo|editor.save|editor.run|global.new_file|editor.editable|editor.search|editor.refresh|global.terminal|global.settings"

    val editorCommands: List<Command>
        get() = Settings.action_items.split("|").mapNotNull { CommandProvider.getForId(it) }

    fun addEditorToolbarCommand(commandId: String, index: Int? = null) {
        val items = Settings.action_items.split("|").toMutableList()

        if (commandId in items) {
            if (index == null) return

            items.remove(commandId)
            items.add(index.coerceIn(0, items.size), commandId)
            Settings.action_items = items.joinToString("|")
            return
        }

        if (index != null) {
            items.add(index.coerceIn(0, items.size), commandId)
        } else {
            items.add(commandId)
        }

        Settings.action_items = items.joinToString("|")
    }

    fun addEditorToolbarCommand(command: Command, index: Int? = null) {
        addEditorToolbarCommand(command.id, index)
    }

    fun removeEditorToolbarCommand(commandId: String) {
        val items = Settings.action_items.split("|").toMutableList()

        if (!items.remove(commandId)) {
            return
        }

        Settings.action_items = items.joinToString("|")
    }

    fun removeEditorToolbarCommand(command: Command) {
        removeEditorToolbarCommand(command.id)
    }

    private var _globalCommands: SnapshotStateList<Command> = mutableStateListOf(CommandProvider.SettingsCommand)

    val globalCommands: List<Command>
        get() = _globalCommands

    fun addGlobalToolbarCommand(command: Command, index: Int? = null) {
        val existingIndex = _globalCommands.indexOf(command)

        if (existingIndex != -1) {
            _globalCommands.removeAt(existingIndex)
        }

        val insertIndex =
            when {
                index != null -> index
                existingIndex != -1 -> existingIndex
                else -> _globalCommands.size
            }

        _globalCommands.add(insertIndex.coerceIn(0, _globalCommands.size), command)
    }

    fun addGlobalToolbarCommand(commandId: String, index: Int? = null) {
        val command = CommandProvider.getForId(commandId) ?: return
        addGlobalToolbarCommand(command, index)
    }

    fun removeGlobalToolbarCommand(command: Command) {
        _globalCommands.remove(command)
    }

    fun removeGlobalToolbarCommand(commandId: String) {
        val command = CommandProvider.getForId(commandId) ?: return
        removeGlobalToolbarCommand(command)
    }
}
