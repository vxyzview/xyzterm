package com.xyzterm.file

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class FileProperty(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
)

fun interface FilePropertiesProvider {
    @Composable fun provideProperties(file: FileObject): List<FileProperty>
}

object FilePropertiesRegistry {

    private val providers = mutableListOf<FilePropertiesProvider>()

    fun register(provider: FilePropertiesProvider) {
        providers += provider
    }

    fun unregister(provider: FilePropertiesProvider) {
        providers -= provider
    }

    @Composable
    fun getProperties(file: FileObject): List<FileProperty> {
        return providers.flatMap { it.provideProperties(file) }
    }
}
