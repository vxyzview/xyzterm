package com.rk.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rk.extension.model.PackageCache
import com.rk.file.FileOperations
import com.rk.file.FileWrapper
import com.rk.file.child
import com.rk.file.themeDir
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.utils.ContrastUtils
import com.rk.utils.errorDialog
import com.rk.utils.logError
import com.rk.utils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.util.Properties
import kotlinx.serialization.json.JsonElement as KJsonElement

internal const val THEME_MIN_CONTRAST_RATIO = 3.0

internal fun themeContrastRatio(manifest: ThemeManifest): Double? =
    listOfNotNull(manifest.light, manifest.dark)
        .mapNotNull { palette ->
            val bgHex = palette.terminalColors?.get("background") ?: palette.baseColors?.background
            val fgHex = palette.terminalColors?.get("foreground") ?: palette.baseColors?.onBackground
            val bg = bgHex?.let { runCatching { it.toColorInt() }.getOrNull() } ?: return@mapNotNull null
            val fg = fgHex?.let { runCatching { it.toColorInt() }.getOrNull() } ?: return@mapNotNull null
            ContrastUtils.ratio(fg, bg)
        }
        .minOrNull()

class ThemeManager(private val context: Application) : CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    val loadedThemes = mutableStateListOf<ThemeHolder>().apply { addAll(builtInThemes) }
    val localThemes = mutableStateMapOf<String, LocalTheme>()

    fun isInstalled(id: String) = localThemes.containsKey(id)

    fun uninstallTheme(theme: ThemeHolder) {
        val localTheme = localThemes[theme.id] ?: return
        File(localTheme.installPath).deleteRecursively()

        loadedThemes.remove(theme)
    }

    private suspend fun calcSize(dir: File): Long {
        return FileOperations.calculateContent(FileWrapper(dir)).totalSize
    }

    private fun resolveCache(dir: File): PackageCache {
        val cacheFile = dir.resolve("cache.json")

        if (!cacheFile.exists() || !cacheFile.isFile) {
            return PackageCache()
        }

        return runCatching {
            json.decodeFromString<PackageCache>(cacheFile.readText())
        }
            .getOrElse {
                PackageCache()
            }
    }

    private fun writeCache(dir: File, cache: PackageCache) {
        val cacheFile = dir.resolve("cache.json")
        cacheFile.writeText(json.encodeToString(cache))
    }

    private suspend fun finishThemeInstall(manifest: ThemeManifest, sourceDir: File?) {
        val installDir = themeDir().child(manifest.id).also { if (!it.exists()) it.mkdirs() }

        var oldCreatedAt: Long? = null
        if (installDir.exists()) {
            oldCreatedAt = resolveCache(installDir).createdAt
        }

        val manifestFile = installDir.resolve("manifest.json")
        manifestFile.writeText(json.encodeToString<ThemeManifest>(manifest))

        sourceDir?.listFiles()?.forEach { file ->
            if (file.name != "manifest.json") {
                file.copyRecursively(installDir.resolve(file.name), overwrite = true)
            }
        }

        val size = calcSize(installDir)
        val newCache =
            PackageCache(
                createdAt = oldCreatedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                size = size,
            )
        writeCache(installDir, newCache)

        val ratio = themeContrastRatio(manifest)
        if (ratio != null && ratio < THEME_MIN_CONTRAST_RATIO) {
            errorDialog(
                title = strings.theme_low_contrast_title.getString(),
                msg = strings.theme_low_contrast_msg.getString(),
            )
        }
    }

    @Suppress("DEPRECATION") // migration path uses deprecated legacy theme APIs
    suspend fun indexLocalThemes() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val themeDir = themeDir()
            if (!themeDir.exists()) return@withContext

            migrateOldThemes(themeDir)

            val newLocalThemes = mutableMapOf<String, LocalTheme>()
            val newLoadedThemes = mutableListOf<ThemeHolder>()
            themeDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    runCatching {
                        val manifestFile = dir.resolve("manifest.json")
                        if (manifestFile.exists()) {
                            val manifest = json.decodeFromString<ThemeManifest>(manifestFile.readText())
                            newLoadedThemes.add(manifest.build())

                            val cache = resolveCache(dir)
                            val size = cache.size ?: calcSize(dir).also { writeCache(dir, cache.copy(size = it)) }

                            val theme =
                                LocalTheme(
                                    manifest = manifest,
                                    installPath = dir.absolutePath,
                                    createdAt = cache.createdAt,
                                    updatedAt = cache.updatedAt,
                                    initSize = size,
                                )

                            newLocalThemes[manifest.id] = theme
                        }
                    }
                        .onFailure {
                            logError(it, "Failed to index local themes")
                        }
                }
            }
            withContext(Dispatchers.Main) {
                localThemes.clear()
                localThemes.putAll(newLocalThemes)

                loadedThemes.clear()
                loadedThemes.addAll(builtInThemes)
                loadedThemes.addAll(newLoadedThemes)
            }
        }
    }

    @Deprecated("Migration from old theme format for backwards compatibility")
    @Suppress("DEPRECATION") // migration path uses deprecated legacy theme APIs
    private suspend fun migrateOldThemes(themeDir: File) {
        val listFiles = themeDir.listFiles()
        var migratedCount = 0
        listFiles?.forEach { file ->
            if (file.isFile) {
                runCatching {
                    if (migratedCount == 0) {
                        withContext(Dispatchers.Main) {
                            toast(strings.migrating_themes.getString())
                        }
                    }
                    ObjectInputStream(FileInputStream(file)).use { input ->
                        val oldConfig = input.readObject()
                        if (oldConfig is ThemeConfig) {
                            val manifest =
                                ThemeManifest(
                                    id = oldConfig.id ?: file.name,
                                    name = oldConfig.name ?: file.name,
                                    minAppVersion = oldConfig.minAppVersion,
                                    inheritBase = oldConfig.inheritBase ?: true,
                                    light = oldConfig.light?.let { ThemePaletteNew.fromLegacyPalette(it) },
                                    dark = oldConfig.dark?.let { ThemePaletteNew.fromLegacyPalette(it) },
                                )

                            finishThemeInstall(manifest, null)
                            migratedCount++
                            file.delete()
                        }
                    }
                }
                    .onFailure {
                        file.delete()
                    }
            }
        }

        if (migratedCount > 0) {
            withContext(Dispatchers.Main) {
                toast(strings.theme_migrated.getFilledString(migratedCount))
            }
        }
    }

    private fun String.toColor(): Color {
        return try {
            Color(this.toColorInt())
        } catch (_: Exception) {
            toast("Invalid color: $this")
            Color.Unspecified
        }
    }

    fun ThemeManifest.build(): ThemeHolder {
        fun Map<String, String>.toProperties(): Properties {
            val props = Properties()
            for ((k, v) in this) props[k] = v
            return props
        }

        val lightTokenColors = light?.tokenColors.toTokenColorArray()
        val darkTokenColors = dark?.tokenColors.toTokenColorArray()

        return ThemeHolder(
            id = id,
            name = name,
            inheritBase = inheritBase,
            lightScheme = light?.build(isDarkTheme = false) ?: blueberry.lightScheme,
            darkScheme = dark?.build(isDarkTheme = true) ?: blueberry.darkScheme,
            lightTerminalColors = light?.terminalColors?.toProperties() ?: Properties(),
            darkTerminalColors = dark?.terminalColors?.toProperties() ?: Properties(),
            lightTokenColors = lightTokenColors,
            darkTokenColors = darkTokenColors,
        )
    }

    private fun KJsonElement?.toTokenColorArray(): JsonArray {
        if (this == null) return JsonArray()

        val gsonElement = JsonParser.parseString(this.toString())
        if (gsonElement.isJsonArray) return gsonElement.asJsonArray

        if (gsonElement.isJsonObject) {
            val convertedArray = JsonArray()
            for ((scope, colorHex) in gsonElement.asJsonObject.entrySet()) {
                val item =
                    JsonObject().apply {
                        addProperty("scope", scope)
                        val settings = JsonObject()
                        settings.addProperty("foreground", colorHex.asString)
                        add("settings", settings)
                    }
                convertedArray.add(item)
            }
            return convertedArray
        }

        return JsonArray()
    }

    fun ThemePaletteNew.build(isDarkTheme: Boolean): ColorScheme {
        return if (isDarkTheme) {
            darkColorScheme(
                primary = baseColors?.primary?.toColor() ?: blueberry.darkScheme.primary,
                onPrimary = baseColors?.onPrimary?.toColor() ?: blueberry.darkScheme.onPrimary,
                primaryContainer = baseColors?.primaryContainer?.toColor() ?: blueberry.darkScheme.primaryContainer,
                onPrimaryContainer =
                    baseColors?.onPrimaryContainer?.toColor() ?: blueberry.darkScheme.onPrimaryContainer,
                secondary = baseColors?.secondary?.toColor() ?: blueberry.darkScheme.secondary,
                onSecondary = baseColors?.onSecondary?.toColor() ?: blueberry.darkScheme.onSecondary,
                secondaryContainer =
                    baseColors?.secondaryContainer?.toColor() ?: blueberry.darkScheme.secondaryContainer,
                onSecondaryContainer =
                    baseColors?.onSecondaryContainer?.toColor() ?: blueberry.darkScheme.onSecondaryContainer,
                tertiary = baseColors?.tertiary?.toColor() ?: blueberry.darkScheme.tertiary,
                onTertiary = baseColors?.onTertiary?.toColor() ?: blueberry.darkScheme.onTertiary,
                tertiaryContainer = baseColors?.tertiaryContainer?.toColor() ?: blueberry.darkScheme.tertiaryContainer,
                onTertiaryContainer =
                    baseColors?.onTertiaryContainer?.toColor() ?: blueberry.darkScheme.onTertiaryContainer,
                error = baseColors?.error?.toColor() ?: blueberry.darkScheme.error,
                onError = baseColors?.onError?.toColor() ?: blueberry.darkScheme.onError,
                errorContainer = baseColors?.errorContainer?.toColor() ?: blueberry.darkScheme.errorContainer,
                onErrorContainer = baseColors?.onErrorContainer?.toColor() ?: blueberry.darkScheme.onErrorContainer,
                background = baseColors?.background?.toColor() ?: blueberry.darkScheme.background,
                onBackground = baseColors?.onBackground?.toColor() ?: blueberry.darkScheme.onBackground,
                surface = baseColors?.surface?.toColor() ?: blueberry.darkScheme.surface,
                onSurface = baseColors?.onSurface?.toColor() ?: blueberry.darkScheme.onSurface,
                surfaceVariant = baseColors?.surfaceVariant?.toColor() ?: blueberry.darkScheme.surfaceVariant,
                onSurfaceVariant = baseColors?.onSurfaceVariant?.toColor() ?: blueberry.darkScheme.onSurfaceVariant,
                outline = baseColors?.outline?.toColor() ?: blueberry.darkScheme.outline,
                outlineVariant = baseColors?.outlineVariant?.toColor() ?: blueberry.darkScheme.outlineVariant,
                scrim = baseColors?.scrim?.toColor() ?: blueberry.darkScheme.scrim,
                inverseSurface = baseColors?.inverseSurface?.toColor() ?: blueberry.darkScheme.inverseSurface,
                inverseOnSurface = baseColors?.inverseOnSurface?.toColor() ?: blueberry.darkScheme.inverseOnSurface,
                inversePrimary = baseColors?.inversePrimary?.toColor() ?: blueberry.darkScheme.inversePrimary,
                surfaceTint = baseColors?.surfaceTint?.toColor() ?: blueberry.darkScheme.surfaceTint,
                surfaceDim = baseColors?.surfaceDim?.toColor() ?: blueberry.darkScheme.surfaceDim,
                surfaceBright = baseColors?.surfaceBright?.toColor() ?: blueberry.darkScheme.surfaceBright,
                surfaceContainerLowest =
                    baseColors?.surfaceContainerLowest?.toColor() ?: blueberry.darkScheme.surfaceContainerLowest,
                surfaceContainerLow =
                    baseColors?.surfaceContainerLow?.toColor() ?: blueberry.darkScheme.surfaceContainerLow,
                surfaceContainer = baseColors?.surfaceContainer?.toColor() ?: blueberry.darkScheme.surfaceContainer,
                surfaceContainerHigh =
                    baseColors?.surfaceContainerHigh?.toColor() ?: blueberry.darkScheme.surfaceContainerHigh,
                surfaceContainerHighest =
                    baseColors?.surfaceContainerHighest?.toColor() ?: blueberry.darkScheme.surfaceContainerHighest,
            )
        } else {
            lightColorScheme(
                primary = baseColors?.primary?.toColor() ?: blueberry.lightScheme.primary,
                onPrimary = baseColors?.onPrimary?.toColor() ?: blueberry.lightScheme.onPrimary,
                primaryContainer = baseColors?.primaryContainer?.toColor() ?: blueberry.lightScheme.primaryContainer,
                onPrimaryContainer =
                    baseColors?.onPrimaryContainer?.toColor() ?: blueberry.lightScheme.onPrimaryContainer,
                secondary = baseColors?.secondary?.toColor() ?: blueberry.lightScheme.secondary,
                onSecondary = baseColors?.onSecondary?.toColor() ?: blueberry.lightScheme.onSecondary,
                secondaryContainer =
                    baseColors?.secondaryContainer?.toColor() ?: blueberry.lightScheme.secondaryContainer,
                onSecondaryContainer =
                    baseColors?.onSecondaryContainer?.toColor() ?: blueberry.lightScheme.onSecondaryContainer,
                tertiary = baseColors?.tertiary?.toColor() ?: blueberry.lightScheme.tertiary,
                onTertiary = baseColors?.onTertiary?.toColor() ?: blueberry.lightScheme.onTertiary,
                tertiaryContainer = baseColors?.tertiaryContainer?.toColor() ?: blueberry.lightScheme.tertiaryContainer,
                onTertiaryContainer =
                    baseColors?.onTertiaryContainer?.toColor() ?: blueberry.lightScheme.onTertiaryContainer,
                error = baseColors?.error?.toColor() ?: blueberry.lightScheme.error,
                onError = baseColors?.onError?.toColor() ?: blueberry.lightScheme.onError,
                errorContainer = baseColors?.errorContainer?.toColor() ?: blueberry.lightScheme.errorContainer,
                onErrorContainer = baseColors?.onErrorContainer?.toColor() ?: blueberry.lightScheme.onErrorContainer,
                background = baseColors?.background?.toColor() ?: blueberry.lightScheme.background,
                onBackground = baseColors?.onBackground?.toColor() ?: blueberry.lightScheme.onBackground,
                surface = baseColors?.surface?.toColor() ?: blueberry.lightScheme.surface,
                onSurface = baseColors?.onSurface?.toColor() ?: blueberry.lightScheme.onSurface,
                surfaceVariant = baseColors?.surfaceVariant?.toColor() ?: blueberry.lightScheme.surfaceVariant,
                onSurfaceVariant = baseColors?.onSurfaceVariant?.toColor() ?: blueberry.lightScheme.onSurfaceVariant,
                outline = baseColors?.outline?.toColor() ?: blueberry.lightScheme.outline,
                outlineVariant = baseColors?.outlineVariant?.toColor() ?: blueberry.lightScheme.outlineVariant,
                scrim = baseColors?.scrim?.toColor() ?: blueberry.lightScheme.scrim,
                inverseSurface = baseColors?.inverseSurface?.toColor() ?: blueberry.lightScheme.inverseSurface,
                inverseOnSurface = baseColors?.inverseOnSurface?.toColor() ?: blueberry.lightScheme.inverseOnSurface,
                inversePrimary = baseColors?.inversePrimary?.toColor() ?: blueberry.lightScheme.inversePrimary,
                surfaceTint = baseColors?.surfaceTint?.toColor() ?: blueberry.lightScheme.surfaceTint,
                surfaceDim = baseColors?.surfaceDim?.toColor() ?: blueberry.lightScheme.surfaceDim,
                surfaceBright = baseColors?.surfaceBright?.toColor() ?: blueberry.lightScheme.surfaceBright,
                surfaceContainerLowest =
                    baseColors?.surfaceContainerLowest?.toColor() ?: blueberry.lightScheme.surfaceContainerLowest,
                surfaceContainerLow =
                    baseColors?.surfaceContainerLow?.toColor() ?: blueberry.lightScheme.surfaceContainerLow,
                surfaceContainer = baseColors?.surfaceContainer?.toColor() ?: blueberry.lightScheme.surfaceContainer,
                surfaceContainerHigh =
                    baseColors?.surfaceContainerHigh?.toColor() ?: blueberry.lightScheme.surfaceContainerHigh,
                surfaceContainerHighest =
                    baseColors?.surfaceContainerHighest?.toColor() ?: blueberry.lightScheme.surfaceContainerHighest,
            )
        }
    }
}
