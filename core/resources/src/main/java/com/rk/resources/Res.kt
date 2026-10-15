package com.rk.resources

import android.app.Application
import android.app.AppGlobals
import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.DelicateCoroutinesApi

typealias drawables = R.drawable

typealias strings = R.string

typealias plurals = R.plurals

@OptIn(DelicateCoroutinesApi::class)
object Res {
    @JvmField var application: Application? = null
}

/**
 * The Application, however early we are asked.
 *
 * `Res.application` is only assigned in `App.onCreate`, but ContentProviders initialise before
 * that — and `core:main` declares a `DocumentsProvider`. Anything its class-init chain touches
 * (Settings, which reads BuiltinFileType, whose enum entries call `getString()`) would otherwise
 * hit `Res.application!!` while it is still null, and die with an ExceptionInInitializerError
 * before the Application ever runs.
 *
 * `AppGlobals.getInitialApplication()` is available from the very start of the process, so this
 * closes that window without moving any initialisation order around.
 */
fun resolveApplication(): Application? = Res.application ?: AppGlobals.getInitialApplication()

fun Int.getString(context: Context = requireNotNull(resolveApplication()) { "Application is not initialized yet" }): String {
    return ContextCompat.getString(context, this)
}

fun Int.getFilledString(vararg args: Any?, context: Context = requireNotNull(resolveApplication()) { "Application is not initialized yet" }): String {
    return this.getString(context).fillPlaceholders(*args)