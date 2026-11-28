package com.xyzterm.bridge

/**
 * Thin JNI adapter to the Rust native core. No runtime logic lives here:
 * Kotlin owns only the Android platform boundary.
 *
 * The library is packaged by CI from `rust/app` (libxyzterm.so). Units not yet
 * ported to Rust keep using their existing implementation; see rust/MAP.md.
 */
object XyztermNative {
    init {
        System.loadLibrary("xyzterm")
    }

    @JvmStatic external fun initialize(): Boolean

    @JvmStatic external fun shutdown()

    @JvmStatic external fun isReady(): Boolean

    @JvmStatic external fun comparePaths(
        left: String,
        right: String,
    ): Int

    @JvmStatic external fun resolvePath(
        base: String,
        path: String,
        flags: Int,
    ): String

    @JvmStatic external fun l2sFinalName(
        intermediate: String,
        counter: Int,
    ): String

    @JvmStatic external fun verifyRootfs(
        archive: String,
        expectedSha256: String,
    ): Boolean

    const val PATHS_ARE_EQUAL: Int = 0
    const val PATH1_IS_PREFIX: Int = 1
    const val PATH2_IS_PREFIX: Int = 2
    const val PATHS_ARE_NOT_COMPARABLE: Int = 3
    const val AT_EMPTY_PATH: Int = 0x1000
    const val AT_SYMLINK_FOLLOW: Int = 0x2000
}
