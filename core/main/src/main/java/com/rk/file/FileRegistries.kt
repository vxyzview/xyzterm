package com.rk.file

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.concurrent.CopyOnWriteArrayList

data class FileDecoration(
    val color: Color? = null,
    val badge: String? = null,
)

fun interface FileDecorationProvider {
    @Composable fun provideDecoration(file: FileObject): FileDecoration?
}

object FileDecorationRegistry {

    // CopyOnWrite: registered rarely, iterated on every composable that renders a file.
    private val providers = CopyOnWriteArrayList<FileDecorationProvider>()

    fun register(provider: FileDecorationProvider) {
        providers += provider
    }

    fun unregister(provider: FileDecorationProvider) {
        providers -= provider
    }

    @Composable
    fun getDecoration(file: FileObject): FileDecoration {
        val decorations = providers.mapNotNull { it.provideDecoration(file) }

        return FileDecoration(
            color = decorations.firstNotNullOfOrNull { it.color },
            badge = decorations.firstNotNullOfOrNull { it.badge },
        )
    }
}

data class FileProperty(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
)

fun interface FilePropertiesProvider {
    @Composable fun provideProperties(file: FileObject): List<FileProperty>
}

object FilePropertiesRegistry {

    private val providers = CopyOnWriteArrayList<FilePropertiesProvider>()

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
