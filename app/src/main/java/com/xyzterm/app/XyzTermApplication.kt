package com.xyzterm.app

import com.xyzterm.App
import com.xyzterm.TerminalFeature
import com.xyzterm.feature.FeatureRegistry

class XyzTermApplication : App() {
    override fun onCreate() {
        super.onCreate()

        // Release builds use the proven Kotlin path. The Rust shadow harness is
        // opt-in for development via `rust-anything.mode=shadow|new`, so production
        // startup does not load JNI or run a comparison on every session.
        val requestedMode = System.getProperty("rust-anything.mode")
        System.setProperty(
            "rust-anything.mode",
            if (requestedMode == "shadow" || requestedMode == "new") requestedMode else "old",
        )

        // Register pluggable features. This build only ships the terminal.
        FeatureRegistry.register(TerminalFeature())

        // Initialize features
        FeatureRegistry.initFeatures(this)
    }
}
