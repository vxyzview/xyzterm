package com.xyzterm.app

import com.xyzterm.App
import com.xyzterm.TerminalFeature
import com.xyzterm.feature.FeatureRegistry

class XyzTermApplication : App() {
    override fun onCreate() {
        super.onCreate()

        // Register pluggable features. This build only ships the terminal.
        FeatureRegistry.register(TerminalFeature())

        // Initialize features
        FeatureRegistry.initFeatures(this)
    }
}
