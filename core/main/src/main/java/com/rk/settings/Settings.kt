package com.rk.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.rk.commands.ToolbarConfiguration
import com.rk.feature.FeatureRegistry
import com.rk.theme.blueberry
import com.rk.utils.application
import com.xyzterm.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

// NOTE: USE snake_case FOR KEYS!
object Settings {
    var shown_disclaimer by CachedPreference("shown_disclaimer", false)
    var amoled by CachedPreference("amoled", false)
    var monet by CachedPreference("monet", false)
    var check_for_update by CachedPreference("check_update", false)
    var is_app_font_asset by CachedPreference("is_app_font_asset", false)
    var is_terminal_font_asset by CachedPreference("is_terminal_font_asset", false)
    var ignore_storage_permission by CachedPreference("ignore_storage_permission", false)
    private var _anr_watchdog by CachedPreference("anr", BuildConfig.DEBUG)
    var anr_watchdog: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _anr_watchdog
        set(value) {
            _anr_watchdog = value
        }

    private var _strict_mode by CachedPreference("strict_mode", BuildConfig.DEBUG)
    var strict_mode: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _strict_mode
        set(value) {
            _strict_mode = value
        }

    var expose_home_dir by CachedPreference("expose_home_dir", false)

    private var _verbose_error by CachedPreference("verbose_error", BuildConfig.DEBUG)
    var verbose_error: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _verbose_error
        set(value) {
            _verbose_error = value
        }
    var terminate_sessions_on_exit by CachedPreference("terminate_sessions_on_exit", false)
    var auto_backup by CachedPreference("auto_backup", false)
    var donated by CachedPreference("donated", false)
    var sandbox by CachedPreference("sandbox", true)
    var seccomp_mode by CachedPreference("seccomp_mode", "unspecified")
    var custom_bindings by CachedPreference("custom_bindings", "[]")
    private var _desktop_mode by CachedPreference("desktop_mode", false)
    var desktop_mode: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _desktop_mode
        set(value) {
            _desktop_mode = value
        }

    private var _theme_flipper by CachedPreference("theme_flipper", false)
    var theme_flipper: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _theme_flipper
        set(value) {
            _theme_flipper = value
        }
    var fullscreen by CachedPreference("fullscreen", false)
    var smart_toolbar by CachedPreference("smart_toolbar", false)
    var confirm_exit by CachedPreference("confirm_exit", true)
    var terminal_keep_screen_on by CachedPreference("terminal_keep_screen_on", true)
    var terminal_show_extra_keys by CachedPreference("terminal_show_extra_keys", true)
    var terminal_clipboard_keybindings by CachedPreference("terminal_clipboard_keybindings", true)
    var terminal_snippets by CachedPreference("terminal_snippets", "[]")

    private var _enable_logcat by CachedPreference("enable_logcat", false)
    var enable_logcat: Boolean
        get() = FeatureRegistry.isEnabled("debug_mode") && _enable_logcat
        set(value) {
            _enable_logcat = value
        }

    // Int settings
    var theme_mode by
        CachedPreference(
            "default_night_mode",
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )
    var terminal_font_size by CachedPreference("terminal_font_size", 13)
    var terminal_scrollback_buffer by CachedPreference("terminal_scrollback_buffer", 5000)

    // String settings
    var theme by CachedPreference("theme", blueberry.id)
    var icon_pack: String by CachedPreference("icon_pack", "")
    var app_font_path by CachedPreference("app_font_path", "")
    var terminal_font_path by CachedPreference("terminal_font_path", "")
    var terminal_cursor_style by CachedPreference("terminal_cursor_style", "block")
    var terminal_extra_keys by CachedPreference("terminal_extra_keys", DEFAULT_TERMINAL_EXTRA_KEYS)
    var current_lang: String by
        CachedPreference("current_lang", application!!.resources.configuration.locales[0].language)

    // Long settings
    var last_version_code by CachedPreference("last_version_code", -1L)

    var action_items by
        CachedPreference(
            "action_items",
            ToolbarConfiguration.DEFAULT_EDITOR_TOOLBAR_COMMANDS,
        )
}

object Preference {
    private var sharedPreferences: SharedPreferences =
        application!!.getSharedPreferences("Settings", Context.MODE_PRIVATE)

    val preferenceTypes: Map<String, KClass<*>> by lazy {
        Settings::class
            .declaredMemberProperties
            .mapNotNull { prop ->
                try {
                    prop.isAccessible = true
                    val delegate = prop.getDelegate(Settings)
                    if (delegate is CachedPreference<*>) {
                        delegate.key to delegate.defaultValue!!::class
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            .toMap()
    }

    // Registry mapping preference keys to their CachedPreference delegates so that
    // external Preference.setXxx() calls can propagate updates into the MutableState.
    private val delegateRegistry = ConcurrentHashMap<String, CachedPreference<*>>()

    internal fun registerDelegate(key: String, delegate: CachedPreference<*>) {
        delegateRegistry[key] = delegate
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> notifyDelegate(key: String, value: T) {
        (delegateRegistry[key] as? CachedPreference<T>)?.applyStateValue(value)
    }

    // Weak reference caches to allow garbage collection of unused settings
    private val stringCache = ConcurrentHashMap<String, WeakReference<String?>>()
    private val boolCache = ConcurrentHashMap<String, Boolean>()
    private val intCache = ConcurrentHashMap<String, Int>()
    private val longCache = ConcurrentHashMap<String, Long>()
    private val floatCache = ConcurrentHashMap<String, Float>()

    fun getAll(): Map<String, Any?> {
        return sharedPreferences.all
    }

    fun put(key: String, value: Any) {
        when (value) {
            is String -> setString(key, value)
            is Boolean -> setBoolean(key, value)
            is Int -> setInt(key, value)
            is Long -> setLong(key, value)
            is Float -> setFloat(key, value)
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }

    fun clearData() {
        sharedPreferences.edit(commit = true) { clear() }
        clearCaches()
        // Snap every registered delegate back to its default so open screens
        // stop showing pre-clear values until the process restarts.
        delegateRegistry.values.forEach { it.resetToDefault() }
    }

    fun clearCaches() {
        stringCache.clear()
        boolCache.clear()
        intCache.clear()
        longCache.clear()
        floatCache.clear()
    }

    fun removeKey(key: String) {
        if (sharedPreferences.contains(key).not()) {
            return
        }

        sharedPreferences.edit { remove(key) }
        clearKeyFromCache(key)
    }

    private fun clearKeyFromCache(key: String) {
        stringCache.remove(key)
        boolCache.remove(key)
        intCache.remove(key)
        longCache.remove(key)
        floatCache.remove(key)
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        return boolCache[key]
            ?: run {
                val value =
                    try {
                        sharedPreferences.getBoolean(key, default)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        setBoolean(key, default)
                        default
                    }
                boolCache[key] = value
                value
            }
    }

    fun setBoolean(key: String, value: Boolean) {
        notifyDelegate(key, value)
        boolCache[key] = value
        runCatching {
            sharedPreferences.edit {
                putBoolean(
                    key,
                    value,
                )
            }
        }
            .onFailure { it.printStackTrace() }
    }

    fun getString(key: String, default: String): String {
        return stringCache[key]?.get()
            ?: run {
                val value =
                    try {
                        sharedPreferences.getString(key, default) ?: default
                    } catch (e: Exception) {
                        e.printStackTrace()
                        setString(key, default)
                        default
                    }
                stringCache[key] = WeakReference(value)
                value
            }
    }

    fun setString(key: String, value: String?) {
        notifyDelegate(key, value)
        stringCache[key] = WeakReference(value)
        runCatching {
            sharedPreferences.edit {
                putString(
                    key,
                    value,
                )
            }
        }
            .onFailure { it.printStackTrace() }
    }

    fun getInt(key: String, default: Int): Int {
        return intCache[key]
            ?: run {
                val value =
                    try {
                        sharedPreferences.getInt(key, default)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        setInt(key, default)
                        default
                    }
                intCache[key] = value
                value
            }
    }

    fun setInt(key: String, value: Int) {
        notifyDelegate(key, value)
        intCache[key] = value
        runCatching {
            sharedPreferences.edit {
                putInt(
                    key,
                    value,
                )
            }
        }
            .onFailure { it.printStackTrace() }
    }

    fun getLong(key: String, default: Long): Long {
        return longCache[key]
            ?: run {
                val value =
                    try {
                        sharedPreferences.getLong(key, default)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        setLong(key, default)
                        default
                    }
                longCache[key] = value
                value
            }
    }

    fun setLong(key: String, value: Long) {
        notifyDelegate(key, value)
        longCache[key] = value
        runCatching {
            sharedPreferences.edit {
                putLong(
                    key,
                    value,
                )
            }
        }
            .onFailure { it.printStackTrace() }
    }

    fun getFloat(key: String, default: Float): Float {
        return floatCache[key]
            ?: run {
                val value =
                    try {
                        sharedPreferences.getFloat(key, default)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        setFloat(key, default)
                        default
                    }
                floatCache[key] = value
                value
            }
    }

    fun setFloat(key: String, value: Float) {
        notifyDelegate(key, value)
        floatCache[key] = value
        runCatching {
            sharedPreferences.edit {
                putFloat(
                    key,
                    value,
                )
            }
        }
            .onFailure { it.printStackTrace() }
    }
}

class CachedExtensionPreference<T>(
    id: String,
    key: String,
    defaultValue: T,
) : CachedPreference<T>("$id.$key", defaultValue)

@Suppress("UNCHECKED_CAST")
open class CachedPreference<T>(val key: String, val defaultValue: T) : ReadWriteProperty<Any?, T> {
    private var state by mutableStateOf(loadInitialValue())

    init {
        Preference.registerDelegate(key, this)
    }

    private fun loadInitialValue(): T {
        return when (defaultValue) {
            is Boolean -> Preference.getBoolean(key, defaultValue) as T
            is String -> Preference.getString(key, defaultValue) as T
            is Int -> Preference.getInt(key, defaultValue) as T
            is Long -> Preference.getLong(key, defaultValue) as T
            is Float -> Preference.getFloat(key, defaultValue) as T
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        when (value) {
            is Boolean -> Preference.setBoolean(key, value)
            is String -> Preference.setString(key, value)
            is Int -> Preference.setInt(key, value)
            is Long -> Preference.setLong(key, value)
            is Float -> Preference.setFloat(key, value)
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }

    internal fun applyStateValue(value: T) {
        state = value
    }

    internal fun resetToDefault() {
        state = defaultValue
    }
}

const val DEFAULT_TERMINAL_EXTRA_KEYS =
    "[\n  [\n    \"ESC\",\n    {\n      \"key\": \"/\",\n      \"popup\": \"\\\\\"\n    },\n    {\n      \"key\": \"-\",\n      \"popup\": \"|\"\n    },\n    \"HOME\",\n    \"UP\",\n    \"END\",\n    \"PGUP\"\n  ],\n  [\n    \"TAB\",\n    \"CTRL\",\n    \"ALT\",\n    \"LEFT\",\n    \"DOWN\",\n    \"RIGHT\",\n    \"PGDN\"\n  ]\n]"
