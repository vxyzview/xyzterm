package com.xyzterm.app

import com.xyzterm.App
import com.xyzterm.TerminalFeature
import com.xyzterm.feature.FeatureRegistry

class XyzTermApplication : App() {
    override fun onCreate() {
        super.onCreate()

        // rust-anything JNI shadow: when the Rust library is bundled, run it beside the
        // original Kotlin logic and log any mismatch; otherwise keep the original path.
        // Set the system property `rust-anything.mode=new` to switch fully to Rust.
        System.setProperty(
            "rust-anything.mode",
            if (runCatching { System.loadLibrary("terminal_rs") }.isSuccess) "shadow" else "old",
        )

        // Register pluggable features. This build only ships the terminal.
        FeatureRegistry.register(TerminalFeature())

        // Initialize features
        FeatureRegistry.initFeatures(this)
    }
}
