package com.rk.commands

import android.util.Log
import android.view.KeyEvent
import com.rk.DefaultScope
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.keybinds.KeyUtils
import com.rk.utils.application
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private const val TAG = "KeybindingsManager"
    @Deprecated("This is now saved as a file.") private const val KEY_KEYBINDINGS = "keybindings"
    private val keybindingsFile = application!!.filesDir.resolve("keybindings.json")

    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }
    private val serializer = MapSerializer(String.serializer(), KeyCombination.serializer())

    /**
     * ConcurrentHashMap, not mutableMapOf.
     *
     * `loadKeybindings` runs on Dispatchers.IO and did `customKeybinds.clear(); putAll(...)`,
     * while `buildKeybindMap()` and `getKeyCombinationForCommand` iterate the same map from the
     * main thread during composition (KeybindingsScreen). HashMap has no detection for that and
     * you get a torn read or a ConcurrentModificationException.
     */
    private val customKeybinds = ConcurrentHashMap<String, KeyCombination>()

    @Volatile private var _keybindMap: MutableMap<KeyCombination, String>? = null

    /**
     * Double-checked under a lock.
     *
     * The previous form was `if (_keybindMap == null) { _keybindMap = buildKeybindMap() }
     * return _keybindMap!!`, and `invalidate()` nulls the field from the main thread via
     * `CommandProvider.registerBuiltin` while a background build is in flight. The `!!` then
     * threw on a value that was non-null one line earlier.
     */
    private val keybindMap: Map<KeyCombination, String>
        get() =
            _keybindMap ?: synchronized(this) {
                _keybindMap ?: buildKeybindMap().also { _keybindMap = it }
            }

    fun invalidate() {
        synchronized(this) { _keybindMap = null }
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

    /**
     * Writing the whole map to disk.
     *
     * Called from `editCustomKey`/`resetCustomKey`/`resetAllKeys`, which fire from click
     * handlers in KeybindingsScreen — so this was a main-thread file write, and a StrictMode
     * disk-write violation, on every keybind edit.
     */
    fun saveKeybindings() {
        val encoded = json.encodeToString(serializer, customKeybinds)
        DefaultScope.launch(Dispatchers.IO) {
            runCatching { keybindingsFile.writeText(encoded) }
                .onFailure { Log.w(TAG, "could not persist keybindings", it) }
        }
    }

    fun loadKeybindings() {
        if (!keybindingsFile.exists()) return

        val content = keybindingsFile.readText()
        if (content.isEmpty()) return

        customKeybinds.clear()
        customKeybinds.putAll(json.decodeFromString(serializer, content))
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
