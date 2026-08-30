package com.rk.extension

import android.app.Activity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.lang.ref.WeakReference

/**
 * One seam for "what's the current resumed activity, and is it foregrounded?".
 *
 * Replaces two parallel globals: the old [ActivityProvider] (flipped in onPause,
 * nulled on destroy) and [com.rk.activities.terminal.Terminal.Companion.instance]
 * (set in onCreate/onResume, survived onStop). They diverged on lifecycle, and
 * every caller guessed which one to read. This is the single source of truth;
 * construct once in [com.rk.App.onCreate] and route both readers through it.
 *
 * Reads return null while no activity is resumed; writes happen from the
 * [ActivityLifecycleAdapter] (mirror the old behaviour: nulled on pause,
 * set on resume) and from the terminal activity's onResume/onStop (mirroring
 * the old `Terminal.isForeground` flip).
 */
class AppActivityScope {
    private var activityRef: WeakReference<Activity>? = null

    /** The currently resumed activity, or null while paused/destroyed. */
    val currentActivity: Activity?
        get() = activityRef?.get()

    /** True between the terminal activity's onResume and onStop. Mirrors the old `Terminal.isForeground`. */
    val isForeground: MutableState<Boolean> = mutableStateOf(false)

    internal fun setCurrent(activity: Activity?) {
        activityRef = if (activity == null) null else WeakReference(activity)
    }

    fun markForeground(activity: Activity) {
        setCurrent(activity)
        isForeground.value = true
    }

    fun markBackground() {
        // Don't null currentActivity here: the lifecycle adapter owns that.
        // Terminal sets the foreground bit on onStop; the adapter clears the
        // activity reference on the matching onPause.
        isForeground.value = false
    }
}