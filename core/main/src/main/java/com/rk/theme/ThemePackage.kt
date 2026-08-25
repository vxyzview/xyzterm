package com.rk.theme

import com.rk.extension.model.Package

data class LocalTheme(
    val manifest: ThemeManifest,
    val installPath: String,
) : Package {
    override val id: String
        get() = manifest.id

    override val name: String
        get() = manifest.name
}
