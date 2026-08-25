package com.rk.file

import android.content.Context
import com.rk.utils.application
import java.io.File

fun getPrivateDir(context: Context = application!!): File {
    return context.filesDir.createDirIfNot().parentFile!!.createDirIfNot()
}

fun getCacheDir(context: Context = application!!): File {
    return context.cacheDir.createDirIfNot()
}

fun localDir(context: Context = application!!): File {
    return getPrivateDir(context).child("local").also { it.createDirIfNot() }
}

fun localBinDir(context: Context = application!!): File {
    return localDir(context).child("bin").also { it.createDirIfNot() }
}

fun localLibDir(context: Context = application!!): File {
    return localDir(context).child("lib").also { it.createDirIfNot() }
}

fun sandboxDir(context: Context = application!!): File {
    return localDir(context).child("sandbox").also { it.createDirIfNot() }
}

fun sandboxHomeDir(context: Context = application!!): File {
    return localDir(context).child("home").createDirIfNot()
}

/** Marker file whose presence proves the rootfs finished extracting successfully. */
const val TERMINAL_SETUP_OK_MARKER = ".terminal_setup_ok_DO_NOT_REMOVE"

/** Entries under [sandboxDir] that make up an extracted rootfs, excluding the bundled home dir and tmp. */
fun rootfsFiles(context: Context = application!!): List<File> =
    sandboxDir(context)
        .listFiles()
        ?.filter { it.absolutePath !in setOf(sandboxHomeDir(context).absolutePath, sandboxDir(context).child("tmp").absolutePath) }
        ?: emptyList()


fun themeDir(context: Context = application!!): File {
    return localDir(context).child("themes").createDirIfNot()
}
