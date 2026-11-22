package com.xyzterm.animations

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

// NavHost's transition lambdas are not composable scopes, so callers pass the
// reduce-motion flag read from CompositionLocals. A reduced-motion user gets a
// plain crossfade instead of a slide.
object NavigationAnimationTransitions {
    fun popEnterTransition(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(250)) + slideInHorizontally { -it / 2 }

    fun popExitTransition(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(tween(0)) else fadeOut(tween(200)) + slideOutHorizontally { it / 2 }

    fun enterTransition(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(250)) + slideInHorizontally { it / 2 }

    fun exitTransition(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(tween(0)) else fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
}
