package com.xyzterm.terminal

/**
 * [name] is the legacy tarball asset name. New builds ship a pre-extracted
 * tree as rootfs-<abi>.zip / .json instead; [abi] selects those assets.
 */
data class ArchmAsset(val abi: String, val name: String)

val ARCHM_ARM64 = ArchmAsset(abi = "arm64", name = "archm-arm64.bin")
val ARCHM_ARM = ArchmAsset(abi = "arm", name = "archm-arm.bin")

/** archm ships only arm64 and armv7; x86 devices have no rootfs available. */
fun archmAssetForAbi(abis: Array<String>): ArchmAsset? =
    when {
        abis.any { it.contains("arm64-v8a") } -> ARCHM_ARM64
        abis.any { it.contains("armeabi-v7a") } -> ARCHM_ARM
        else -> null
    }

/** Pick an asset that is actually bundled in this ABI-specific APK. */
fun pickRootfsAsset(preferred: ArchmAsset?, available: Set<String>): ArchmAsset? {
    fun bundled(asset: ArchmAsset) =
        "rootfs-${asset.abi}.zip" in available || asset.name in available
    return when {
        preferred == null -> null
        bundled(preferred) -> preferred
        else -> listOf(ARCHM_ARM64, ARCHM_ARM).firstOrNull(::bundled)
    }
}
