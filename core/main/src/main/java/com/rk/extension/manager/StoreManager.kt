package com.rk.extension.manager

import com.rk.extension.ICONPACKS_API_BASE
import com.rk.extension.THEMES_API_BASE
import com.rk.icons.pack.IconPackEntry
import com.rk.theme.ThemeEntry
import com.rk.utils.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable data class ThemeListResponse(val themes: List<ThemeEntry>)

@Serializable data class IconPackListResponse(val iconPacks: List<IconPackEntry>)

object StoreManager {
    private val client = okHttpClient

    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    fun getThemeIconUrl(id: String): String = "$THEMES_API_BASE/$id/icon.png"

    fun getThemeReadmeUrl(id: String): String = "$THEMES_API_BASE/$id/README.md"

    fun getThemeChangelogUrl(id: String): String = "$THEMES_API_BASE/$id/CHANGELOG.md"

    fun getIconPackIconUrl(id: String): String = "$ICONPACKS_API_BASE/$id/icon.png"

    fun getIconPackReadmeUrl(id: String): String = "$ICONPACKS_API_BASE/$id/README.md"

    fun getIconPackChangelogUrl(id: String): String = "$ICONPACKS_API_BASE/$id/CHANGELOG.md"

    private fun requestJson(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            res.body.string()
        }
    }

    suspend fun fetchThemes(): List<ThemeEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonString = requestJson(THEMES_API_BASE)
                val response = json.decodeFromString<ThemeListResponse>(jsonString)
                response.themes
            }
                .onFailure {
                    it.printStackTrace()
                }
                .getOrElse { emptyList() }
        }

    suspend fun fetchIconPacks(): List<IconPackEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonString = requestJson(ICONPACKS_API_BASE)
                val response = json.decodeFromString<IconPackListResponse>(jsonString)
                response.iconPacks
            }
                .onFailure {
                    it.printStackTrace()
                }
                .getOrElse { emptyList() }
        }
}
