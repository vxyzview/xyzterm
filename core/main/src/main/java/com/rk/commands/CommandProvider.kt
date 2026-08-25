package com.rk.commands

import androidx.compose.runtime.mutableStateListOf
import com.rk.commands.global.DocumentationCommand
import com.rk.commands.global.SettingsCommand
import com.rk.extension.api.XedExtensionPoint

object CommandProvider {
    private val _commandList = mutableStateListOf<Command>()
    val commandList: List<Command>
        get() = _commandList

    val DocumentationCommand: DocumentationCommand by lazy { DocumentationCommand() }
    val SettingsCommand: SettingsCommand by lazy { SettingsCommand() }

    fun buildCommands() =
        synchronized(this) {
            registerBuiltin(DocumentationCommand)
            registerBuiltin(SettingsCommand)
        }

    private fun <T : Command> registerBuiltin(command: T) {
        if (_commandList.contains(command)) return
        _commandList.add(command)
        KeybindingsManager.invalidate()
    }

    @XedExtensionPoint
    fun registerCommand(command: Command) {
        val index = _commandList.indexOf(command)
        if (index >= 0) {
            _commandList[index] = command
        } else {
            _commandList.add(command)
        }
        KeybindingsManager.invalidate()
    }

    @XedExtensionPoint
    fun unregisterCommand(command: Command) {
        _commandList.remove(command)
        KeybindingsManager.invalidate()
    }

    fun getForId(id: String): Command? = findRecursive(id, commandList)

    fun getParentCommand(command: Command): Command? = findParent(command, commandList)

    private fun findParent(target: Command, commands: List<Command>): Command? {
        for (parent in commands) {
            val children = parent.childCommands
            if (children.any { it.id == target.id }) return parent

            val match = findParent(target, children)
            if (match != null) return match
        }
        return null
    }

    private fun findRecursive(id: String, commands: List<Command>): Command? {
        for (command in commands) {
            if (command.id == id) return command
            val children = command.childCommands

            val match = findRecursive(id, children)
            if (match != null) return match
        }
        return null
    }
}
