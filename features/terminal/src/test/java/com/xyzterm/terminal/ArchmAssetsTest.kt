package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchmAssetsTest {
    @Test
    fun arm64_device_selects_arm64_asset() {
        assertEquals(ARCHM_ARM64, archmAssetForAbi(arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun armv7_device_selects_arm_asset() {
        assertEquals(ARCHM_ARM, archmAssetForAbi(arrayOf("armeabi-v7a")))
    }

    @Test
    fun x86_device_is_unsupported() {
        assertNull(archmAssetForAbi(arrayOf("x86_64", "x86")))
    }
}
