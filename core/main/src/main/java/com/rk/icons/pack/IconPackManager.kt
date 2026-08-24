package com.rk.icons.pack

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.rk.extension.model.PackageCache
import com.rk.file.FileOperations
import com.rk.file.FileWrapper
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class IconPackEntry(
    val id: String,
    val manifest: IconPackManifest,
    val size: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

val currentIconPack = mutableStateOf<LocalIconPack?>(null)
val iconPackDir = localDir().child("icon_pack").also { it.createDirIfNot() }

class IconPackManager(context: Application) : CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    val localIconPacks = mutableStateMapOf<String, LocalIconPack>()
    val storeIconPacks = mutableStateMapOf<String, StoreIconPack>()

    fun isInstalled(id: String) = localIconPacks.containsKey(id)

    fun getIconPackPackage(id: String): IconPackPackage? {
        val local = localIconPacks[id]
        val store = storeIconPacks[id]

        return when {
            local != null && store != null -> UpdatableIconPack(local, store)
            local != null -> local
            store != null -> store
            else -> null
        }
    }

    fun getSyncedIconPacks(): List<IconPackPackage> {
        val allIds = localIconPacks.keys + storeIconPacks.keys
        return allIds.mapNotNull { getIconPackPackage(it) }
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

    suspend fun invalidateSize(pkg: IconPackPackage) {
        if (pkg is StoreIconPack) return

        withContext(Dispatchers.IO) {
            val dir = iconPackDir.resolve(pkg.id)
            val cache = resolveCache(dir)
            val newSize = calcSize(dir)
            writeCache(dir, cache.copy(size = newSize))

            withContext(Dispatchers.Main) {
                localIconPacks[pkg.id]?.size = newSize
            }
        }
    }

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
                            val cache = resolveCache(dir)
                            val size = cache.size ?: calcSize(dir).also { writeCache(dir, cache.copy(size = it)) }

                            val iconPack =
                                LocalIconPack(
                                    manifest = iconPackManifest,
                                    installPath = dir.absolutePath,
                                    createdAt = cache.createdAt,
                                    updatedAt = cache.updatedAt,
                                    initSize = size,
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
