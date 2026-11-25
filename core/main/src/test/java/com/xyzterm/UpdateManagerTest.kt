package com.xyzterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun x86_devices_have_no_update_asset() {
        assertNull(updateApkName("1.0.0", arrayOf("x86_64", "x86")))
    }

    @Test
    fun prerelease_tags_are_still_newer_than_installed() {
        assertTrue(isNewer("v1.1.0-rc1", "v1.0.7"))
        assertFalse(isNewer("v1.0.7", "v1.1.0-rc1"))
        assertFalse(isNewer("v1.2.3.4", "v1.2.3"))
    }

    @Test
    fun final_release_outranks_equal_numbered_prerelease() {
        assertTrue(isNewer("v1.0.7", "v1.0.7-rc1"))
        assertFalse(isNewer("v1.0.7-rc1", "v1.0.7"))
    }

    @Test
    fun unparsable_numeric_components_never_count_as_newer() {
        val huge = "v999999999999999999999.0.0"
        assertFalse(isNewer(huge, "v1.0.7"))
        assertFalse(isNewer("v1.0.7", huge))
    }

    @Test
    fun release_tag_regex_rejects_path_shaped_tags() {
        assertTrue(RELEASE_TAG.matches("v1.0.7"))
        assertTrue(RELEASE_TAG.matches("1.0.7-rc1"))
        assertFalse(RELEASE_TAG.matches("v1.2.3/../../etc/passwd"))
        assertFalse(RELEASE_TAG.matches("../../v1.2.3"))
    }
}
