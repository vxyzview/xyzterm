package com.xyzterm.animations

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

// NavHost's transition lambdas are not composable scopes, so callers pass the
// reduce-motion flag read from CompositionLocals. A reduced-motion user gets a
// plain instant crossfade instead of a slide.
object NavigationAnimationTransitions {
    // Critically damped spring: fast, continuous velocity with no bounce, so a
    // settings trip feels fluid without overshooting into a stutter.
    private val fadeMotion =
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    private val slideMotion =
        spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    fun popEnterTransition(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(tween(0)) else fadeIn(fadeMotion) + slideInHorizontally(slideMotion) { -it / 2 }

    fun popExitTransition(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(tween(0)) else fadeOut(fadeMotion) + slideOutHorizontally(slideMotion) { it / 2 }

    fun enterTransition(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) fadeIn(tween(0)) else fadeIn(fadeMotion) + slideInHorizontally(slideMotion) { it / 2 }

    fun exitTransition(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) fadeOut(tween(0)) else fadeOut(fadeMotion) + slideOutHorizontally(slideMotion) { -it / 2 }
}
