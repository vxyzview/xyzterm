package com.rk.icons.pack

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.rk.file.child
import com.rk.file.createDirIfNot
import com.rk.file.localDir
import com.rk.settings.Settings
import com.rk.utils.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

val currentIconPack = mutableStateOf<LocalIconPack?>(null)
val iconPackDir: File by lazy { localDir().child("icon_pack").also { it.createDirIfNot() } }

class IconPackManager(context: Application) : CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    val localIconPacks = mutableStateMapOf<String, LocalIconPack>()

    fun isInstalled(id: String) = localIconPacks.containsKey(id)

    fun uninstallIconPack(iconPackId: String) {
        val iconPack = localIconPacks[iconPackId] ?: return
        File(iconPack.installPath).deleteRecursively()
        localIconPacks.remove(iconPackId)
    }

    suspend fun indexLocalPacks() = mutex.withLock {
        val newLocal = mutableMapOf<String, LocalIconPack>()
        withContext(Dispatchers.IO) {
            iconPackDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val manifestJson = dir.resolve("manifest.json")
                    if (manifestJson.exists()) {
                        runCatching {
                            val iconPackManifest = json.decodeFromString<IconPackManifest>(manifestJson.readText())

                            val iconPack =
                                LocalIconPack(
                                    manifest = iconPackManifest,
                                    installPath = dir.absolutePath,
                                )
                            newLocal[iconPackManifest.id] = iconPack
                        }
                            .onFailure {
                                logError(it, "Failed to index local icon pack")
                            }
                    }
                }
            }
        }
        withContext(Dispatchers.Main) {
            localIconPacks.clear()
            localIconPacks.putAll(newLocal)
        }

        if (Settings.icon_pack.isNotEmpty()) {
            currentIconPack.value = localIconPacks[Settings.icon_pack]
        }
    }
}
