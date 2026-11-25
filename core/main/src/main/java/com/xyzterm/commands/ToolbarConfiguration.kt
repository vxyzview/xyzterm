package com.xyzterm.commands

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

object ToolbarConfiguration {
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
