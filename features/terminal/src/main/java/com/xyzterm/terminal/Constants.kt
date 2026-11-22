package com.xyzterm.terminal

private const val ARCHM_TAG = "2026.11.21"
private const val ARCHM_BASE = "https://github.com/vxyzview/archm/releases/download/$ARCHM_TAG"

data class ArchmAsset(val name: String, val url: String, val sha256: String)

val ARCHM_ARM64 =
    ArchmAsset(
        name = "archm-arm64.tar.gz",
        url = "$ARCHM_BASE/archm-arm64.tar.gz",
        sha256 = "f1a7c3c710592d1d9484662c9474b92c17bf27f3c517c596ee5c094011369d0f",
    )

val ARCHM_ARM =
    ArchmAsset(
        name = "archm-arm.tar.gz",
        url = "$ARCHM_BASE/archm-arm.tar.gz",
        sha256 = "5bcaa84710df04bbd38c682a32b3247bcae8f9738fc1851e33b1535a693e6347",
    )

/** archm ships only arm64 and armv7; x86 devices have no rootfs available. */
fun archmAssetForAbi(abis: Array<String>): ArchmAsset? =
    when {
        abis.any { it.contains("arm64-v8a") } -> ARCHM_ARM64
        abis.any { it.contains("armeabi-v7a") } -> ARCHM_ARM
        else -> null
    }
