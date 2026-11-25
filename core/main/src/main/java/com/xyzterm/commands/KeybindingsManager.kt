package com.xyzterm.commands

import android.view.KeyEvent
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Preference
import com.xyzterm.settings.keybinds.KeyUtils
import com.xyzterm.utils.application
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class KeyCombination(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    fun getDisplayName(): String {
        return buildString {
            if (ctrl) append("${strings.ctrl.getString()}-")
            if (shift) append("${strings.shift.getString()}-")
            if (alt) append("${strings.alt.getString()}-")

            append(KeyUtils.getShortDisplayName(keyCode))
        }
    }

    companion object {
        // Native Android KeyEvent
        fun fromEvent(event: KeyEvent): KeyCombination =
            KeyCombination(
                keyCode = event.keyCode,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
            )

        // Compose KeyEvent
        fun fromEvent(event: androidx.compose.ui.input.key.KeyEvent): KeyCombination = fromEvent(event.nativeKeyEvent)
    }
}

object KeybindingsManager {
    @Deprecated("This is now saved as a file.") private const val KEY_KEYBINDINGS = "keybindings"
    private val keybindingsFile = application!!.filesDir.resolve("keybindings.json")

    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }
    private val serializer = MapSerializer(String.serializer(), KeyCombination.serializer())

    private val customKeybinds = mutableMapOf<String, KeyCombination>()

    private var _keybindMap: MutableMap<KeyCombination, String>? = null
    private val keybindMap: MutableMap<KeyCombination, String>
        get() {
            if (_keybindMap == null) {
                _keybindMap = buildKeybindMap()
            }

            return _keybindMap!!
        }

    fun invalidate() {
        _keybindMap = null
    }

    @Deprecated("This is temporary migration code.")
    @Suppress("DEPRECATION") // uses deprecated legacy keybindings storage on purpose
    fun migrate() {
        runCatching {
            val old = Preference.getString(KEY_KEYBINDINGS, "")
            if (old.isEmpty()) return

            val oldActions =
                json.decodeFromString(
                    ListSerializer(KeyAction.serializer()),
                    old,
                )

            val migrated = oldActions.associate { it.commandId to it.keyCombination }

            keybindingsFile.writeText(json.encodeToString(serializer, migrated))

            Preference.removeKey(KEY_KEYBINDINGS)
        }
    }

    fun saveKeybindings() {
        // Write beside the target and replace by rename so a crash mid-write
        // cannot leave a half-written keybindings file behind.
        val temp = Files.createTempFile(keybindingsFile.parentFile.toPath(), "keybindings-", ".tmp")
        try {
            Files.write(temp, json.encodeToString(serializer, customKeybinds).toByteArray())
            Files.move(temp, keybindingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun loadKeybindings() {
        if (!keybindingsFile.exists()) return

        val content = keybindingsFile.readText()
        if (content.isEmpty()) return

        customKeybinds.clear()
        // A corrupt file is left untouched; bad binds must not break startup.
        runCatching { json.decodeFromString(serializer, content) }
            .onSuccess { customKeybinds.putAll(it) }
        invalidate()
    }

    fun conflictsWithExisting(keyCombination: KeyCombination, command: Command): Boolean {
        return keybindMap[keyCombination]?.let { it != command.id } ?: false
    }

    fun resetCustomKey(commandId: String) {
        customKeybinds.remove(commandId)
        saveKeybindings()
        invalidate()
    }

    fun resetAllKeys() {
        customKeybinds.clear()
        saveKeybindings()
        invalidate()
    }

    fun editCustomKey(commandId: String, keyCombination: KeyCombination) {
        customKeybinds[commandId] = keyCombination
        saveKeybindings()
        invalidate()
    }

    private fun buildKeybindMap(): MutableMap<KeyCombination, String> {
        val map = mutableMapOf<KeyCombination, String>()

        customKeybinds.forEach { (commandId, keyCombination) -> map[keyCombination] = commandId }

        for (command in CommandProvider.commandList) {
            if (command.id in customKeybinds) continue

            command.defaultKeybinds?.let { map[it] = command.id }
        }

        return map
    }

    fun getKeyCombinationForCommand(command: Command): KeyCombination? {
        return customKeybinds[command.id] ?: command.defaultKeybinds
    }

    /**
     * Resolves a pressed key combination to its bound command, if any. This is the runtime half of the keybindings
     * screen: without it, saved binds are persisted but nothing ever fires them.
     */
    fun findCommandForKey(keyCombination: KeyCombination): Command? {
        val commandId = keybindMap[keyCombination] ?: return null
        return CommandProvider.commandList.firstOrNull { it.id == commandId }
    }
}

@Deprecated("Only used for migration from the old keybindings format.")
@Serializable
private data class KeyAction(
    val commandId: String,
    val keyCombination: KeyCombination,
)
