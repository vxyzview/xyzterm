package com.rk.animations

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

// NavHost's transition lambdas are non-composable scopes, so these cannot depend
// on CompositionLocals. Compose's animation framework already honors the system
// "Remove animations" accessibility setting (animator duration scale = 0) on its
// own, so no explicit reduced-motion branch is needed here.
object NavigationAnimationTransitions {
    fun popEnterTransition(): EnterTransition =
        fadeIn(tween(250)) + slideInHorizontally { -it / 2 }

    fun popExitTransition(): ExitTransition =
        fadeOut(tween(200)) + slideOutHorizontally { it / 2 }

    fun enterTransition(): EnterTransition =
        fadeIn(tween(250)) + slideInHorizontally { it / 2 }

    fun exitTransition(): ExitTransition =
        fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
}