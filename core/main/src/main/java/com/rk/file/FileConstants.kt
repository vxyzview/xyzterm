package com.rk.file

import android.content.Context
import com.rk.utils.application
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Memoised per context, because every one of these accessors used to call `mkdirs()` on every
 * invocation — up to four syscalls a call — and they are reached from
 * `DocumentProvider.getFileForDocId` on every `queryDocument`/`openDocument`/`deleteDocument`,
 * including once per candidate file inside the search loop.
 *
 * Keyed by context so the cache cannot hand a directory from a previous Context back.
 */
private val dirCache = ConcurrentHashMap<Context, Map<String, File>>()

private fun dirsFor(context: Context): Map<String, File> =
    dirCache.computeIfAbsent(context) { ctx ->
        val files = ctx.filesDir.createDirIfNot().parentFile!!.createDirIfNot()
        val local = files.child("local").createDirIfNot()
        val sandbox = local.child("sandbox").createDirIfNot()
        val home = local.child("home").createDirIfNot()
        mapOf(
            "private" to files,
            "local" to local,
            "bin" to local.child("bin").createDirIfNot(),
            "lib" to local.child("lib").createDirIfNot(),
            "sandbox" to sandbox,
            "home" to home,
            "themes" to local.child("themes").createDirIfNot(),
            "cache" to ctx.cacheDir.createDirIfNot(),
        )
    }

fun getPrivateDir(context: Context = application!!): File = dirsFor(context).getValue("private")

fun getCacheDir(context: Context = application!!): File = dirsFor(context).getValue("cache")

fun localDir(context: Context = application!!): File = dirsFor(context).getValue("local")

fun localBinDir(context: Context = application!!): File = dirsFor(context).getValue("bin")

fun localLibDir(context: Context = application!!): File = dirsFor(context).getValue("lib")

fun sandboxDir(context: Context = application!!): File = dirsFor(context).getValue("sandbox")

fun sandboxHomeDir(context: Context = application!!): File = dirsFor(context).getValue("home")

fun themeDir(context: Context = application!!): File = dirsFor(context).getValue("themes")

fun persistentTempDir(context: Context = application!!): File =
    getCacheDir(context).child("tempFiles").createDirIfNot()
