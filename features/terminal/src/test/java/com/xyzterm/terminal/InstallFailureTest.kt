package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallFailureTest {
    @Test
    fun no_space_maps_to_storage_advice() {
        assertEquals(InstallFailure.NoSpace, installFailureFor("java.io.IOException: No space left on device"))
        assertEquals(InstallFailure.NoSpace, installFailureFor("ENOSPC (errno 28)"))
    }

    @Test
    fun missing_asset_maps_to_reinstall_advice() {
        assertEquals(
            InstallFailure.MissingAsset,
            installFailureFor("Bundled rootfs is missing from this APK"),
        )
    }

    @Test
    fun unknown_errors_fall_back_to_retry() {
        assertEquals(InstallFailure.Other, installFailureFor(null))
        assertEquals(InstallFailure.Other, installFailureFor("some unexpected I/O problem"))
    }
}
