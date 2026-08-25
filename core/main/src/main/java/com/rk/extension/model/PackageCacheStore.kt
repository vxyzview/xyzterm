package com.rk.extension.model

import com.rk.file.FileOperations
import com.rk.file.FileWrapper
import java.io.File
import kotlinx.serialization.json.Json

internal object PackageCacheStore {
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    suspend fun calcSize(dir: File): Long {
        return FileOperations.calculateContent(FileWrapper(dir)).totalSize
    }

    fun resolveCache(dir: File): PackageCache {
        val cacheFile = dir.resolve("cache.json")

        if (!cacheFile.exists() || !cacheFile.isFile) {
            return PackageCache()
        }

        return runCatching { json.decodeFromString<PackageCache>(cacheFile.readText()) }.getOrElse { PackageCache() }
    }

    fun writeCache(dir: File, cache: PackageCache) {
        val cacheFile = dir.resolve("cache.json")
        cacheFile.writeText(json.encodeToString(cache))
    }
}
