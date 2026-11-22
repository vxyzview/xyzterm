package com.xyzterm

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun arm64_devices_download_the_arm64_asset() {
        assertEquals(
            "xyzterm-1.0.0-arm64.apk",
            updateApkName("1.0.0", arrayOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun armv7_devices_download_the_arm_asset() {
        assertEquals(
            "xyzterm-1.0.0-arm.apk",
            updateApkName("1.0.0", arrayOf("armeabi-v7a")),
        )
    }
}
