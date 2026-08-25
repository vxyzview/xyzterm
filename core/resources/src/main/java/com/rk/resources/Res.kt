package com.rk.resources

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat

typealias drawables = R.drawable

typealias strings = R.string

typealias plurals = R.plurals

object Res {
    @JvmField var application: Application? = null
}

fun Int.getString(context: Context = Res.application!!): String {
    return ContextCompat.getString(context, this)
}

fun Int.getFilledString(vararg args: Any?, context: Context = Res.application!!): String {
    return this.getString(context).fillPlaceholders(*args)
}

fun String.fillPlaceholders(vararg args: Any?): String {
    return String.format(this, *args)
}

fun Int.getQuantityString(quantity: Int, vararg formatArgs: Any?, context: Context = Res.application!!): String {
    return context.resources.getQuantityString(this, quantity, *formatArgs)
}
