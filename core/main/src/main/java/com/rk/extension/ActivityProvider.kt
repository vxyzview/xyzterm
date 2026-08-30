package com.rk.extension

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Mirrors Android lifecycle into the [AppActivityScope]. Constructed once at
 * [com.rk.App.onCreate] with the shared scope; registered as lifecycle callbacks
 * on the application. Not a singleton anymore — call sites that need the
 * current activity go through [AppActivityScope.currentActivity] directly.
 */
class ActivityProvider(
    private val scope: AppActivityScope,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        scope.setCurrent(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (scope.currentActivity == activity) {
            scope.setCurrent(null)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}