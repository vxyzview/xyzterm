package com.xyzterm.file

import android.content.Context
import com.xyzterm.utils.application
import java.io.File

fun getPrivateDir(context: Context = application!!): File {
    return context.filesDir.createDirIfNot().parentFile!!.createDirIfNot()
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
