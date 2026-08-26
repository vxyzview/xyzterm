package com.rk.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Returns a counter that increments every time the host resumes.
 *
 * Terminal settings live in SharedPreferences, not Compose state, so plain
 * `Settings.x` reads inside composition never invalidate. Toggling extra-keys,
 * snippets or the smart toolbar in the settings screen then returning looked
 * like nothing happened until an unrelated recomposition ran. Key a
 * [remember] block on this counter (or just read it in composition) to refresh
 * those reads exactly when control comes back from the settings activity.
 */
@Composable
fun rememberSettingsRefresh(): Int {
    var refresh by remember { mutableIntStateOf(0) }
    // Same resolution strategy as AppDialogHost's rememberHostResumed().
    val owner = LocalContext.current as? LifecycleOwner
    DisposableEffect(owner) {
        if (owner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        refresh++
                    }
                }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }
    return refresh
}
