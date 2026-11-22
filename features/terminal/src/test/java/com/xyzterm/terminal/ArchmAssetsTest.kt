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

    @Test
    fun abi_specific_apk_uses_its_bundled_asset() {
        assertEquals(ARCHM_ARM, pickRootfsAsset(ARCHM_ARM, setOf("rootfs-arm.zip", "rootfs-arm.json")))
    }

    @Test
    fun wrong_abi_apk_falls_back_to_the_bundled_asset() {
        assertEquals(ARCHM_ARM64, pickRootfsAsset(ARCHM_ARM, setOf("rootfs-arm64.zip")))
    }

    @Test
    fun missing_bundled_asset_fails_closed() {
        assertNull(pickRootfsAsset(ARCHM_ARM64, setOf("init.sh")))
    }
}
