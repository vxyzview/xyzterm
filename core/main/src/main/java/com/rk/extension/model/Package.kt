package com.rk.extension.model

import kotlinx.serialization.Serializable

@Serializable
data class PackageCache(
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val size: Long? = null,
)

interface Package {
    val id: String
    val name: String
}
