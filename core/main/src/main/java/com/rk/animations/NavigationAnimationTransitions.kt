package com.rk.animations

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalReducedMotion

object NavigationAnimationTransitions {
    // Honor the system "Remove animations" accessibility setting: finite tweens
    // run at full speed even when reduced motion is on, so fall back to instant
    // (no-op) transitions so screen-reader / vestibular users get a static switch.
    @Composable
    fun popEnterTransition(): EnterTransition =
        if (LocalReducedMotion.current) EnterTransition.None else fadeIn(tween(250)) + slideInHorizontally { -it / 2 }

    @Composable
    fun popExitTransition(): ExitTransition =
        if (LocalReducedMotion.current) ExitTransition.None else fadeOut(tween(200)) + slideOutHorizontally { it / 2 }

    @Composable
    fun enterTransition(): EnterTransition =
        if (LocalReducedMotion.current) EnterTransition.None else fadeIn(tween(250)) + slideInHorizontally { it / 2 }

    @Composable
    fun exitTransition(): ExitTransition =
        if (LocalReducedMotion.current) ExitTransition.None else fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
}
