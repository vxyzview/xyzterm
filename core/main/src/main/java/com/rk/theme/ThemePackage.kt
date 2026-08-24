package com.rk.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rk.common.PackageType
import com.rk.extension.model.Package
import com.rk.extension.model.PackageAuthor
import com.rk.extension.model.Review

interface ThemePackage : Package {
    override val type: PackageType
        get() = PackageType.THEME
}

data class LocalTheme(
    val manifest: ThemeManifest,
    val installPath: String,
    override val createdAt: Long?,
    override val updatedAt: Long?,
    val initSize: Long?,
) : ThemePackage {
    override val id: String
        get() = manifest.id

    override val name: String
        get() = manifest.name

    override val version: String
        get() = manifest.version

    override val author: PackageAuthor
        get() = manifest.author

    override val description: String?
        get() = manifest.description

    override val tags: List<String>
        get() = manifest.tags

    override val repository: String
        get() = manifest.repository

    override val license: String?
        get() = manifest.license

    override val dependencies: List<String>
        get() = emptyList()

    override val recommendations: List<String>
        get() = emptyList()

    override val hasSettings: Boolean
        get() = false

    override val iconUrl: String
        get() = "$installPath/icon.png"

    override val readmeUrl: String
        get() = "$installPath/README.md"

    override val changelogUrl: String
        get() = "$installPath/CHANGELOG.md"

    override val minAppVersion: Int?
        get() = manifest.minAppVersion

    override val supportedArchitectures: List<String>?
        get() = null

    override val downloads: Int?
        get() = null

    override val rating: Float?
        get() = null

    override var size by mutableStateOf(initSize)

    override suspend fun getReviews(): List<Review> = emptyList()
}

