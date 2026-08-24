package com.rk.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandProviderTest {

    @Test
    fun lazyCommandsAccessibleWithoutBuildCommands() {
        assertEquals("global.documentation", CommandProvider.DocumentationCommand.id)
        assertEquals("global.settings", CommandProvider.SettingsCommand.id)
    }

    @Test
    fun buildCommandsRegistersEachBuiltinExactlyOnceInSourceOrder() {
        runCatching { CommandProvider.buildCommands() }
        runCatching { CommandProvider.buildCommands() }

        val ids = CommandProvider.commandList.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(listOf("global.documentation", "global.settings"), ids)
    }
}
