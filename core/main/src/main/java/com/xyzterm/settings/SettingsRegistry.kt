package com.xyzterm.settings

import androidx.compose.runtime.mutableStateListOf
import com.xyzterm.extension.api.DynamicRoute

data class SettingsCategory(
    val labelRes: Int,
    val descriptionRes: Int,
    val iconRes: Int,
    val route: String,
)

object SettingsRegistry {

    private val _categories = mutableStateListOf<SettingsCategory>()
    val categories: List<SettingsCategory>
        get() = _categories.toList()

    private val _routes = mutableStateListOf<DynamicRoute>()
    val routes: List<DynamicRoute>
        get() = _routes.toList()

    fun registerCategory(category: SettingsCategory) {
        _categories.add(category)
    }

    fun unregisterCategory(category: SettingsCategory) {
        _categories.remove(category)
    }

    fun registerRoute(route: DynamicRoute) {
        _routes.add(route)
    }

    fun unregisterRoute(route: DynamicRoute) {
        _routes.remove(route)
    }
}
