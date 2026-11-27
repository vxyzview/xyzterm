// AGP pulls these into the buildscript classpath, where subproject resolution
// rules do not apply. Force the patched versions there as well.
buildscript {
    configurations.configureEach {
        resolutionStrategy {
            force("com.google.guava:guava:33.8.0-jre")
            force("org.bouncycastle:bcprov-jdk18on:1.87")
            force("org.bouncycastle:bcpkix-jdk18on:1.87")
            force("org.apache.commons:commons-lang3:3.22.0")
        }
    }
}

plugins {
    alias(libs.plugins.android.baselineprofile) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktfmt) apply false
}

subprojects {
    // GHSA: force commons-lang3 past the Uncontrolled Recursion CVE (< 3.18.0).
    // Transitive (no direct reference); re-check with Dependabot after bumping.
    configurations.configureEach {
        resolutionStrategy {
            force("org.apache.commons:commons-lang3:3.22.0")
            // Transitive build-time deps pinned below patched versions:
            // GHSA-xxph-c9ww-hj94, GHSA-qp49-qgx5-5m26, GHSA-9pwp-9qqc-pr26,
            // GHSA-9rm7-3qhh-h2mc.
            force("com.google.guava:guava:33.8.0-jre")
            force("org.bouncycastle:bcprov-jdk18on:1.87")
            force("com.squareup.wire:wire-runtime:6.4.7")
        }
    }

    plugins.withId(rootProject.libs.plugins.ktfmt.get().pluginId) {
        configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
            kotlinLangStyle()
            maxWidth.set(120)
        }
    }

    plugins.withId(rootProject.libs.plugins.android.library.get().pluginId) {
        configure<com.android.build.api.dsl.LibraryExtension> {
            defaultConfig {
                externalNativeBuild {
                    cmake {
                        // Force identical linker optimization (O2) on all environments
                        arguments(
                            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-O2",
                            "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-O2",
                        )
                    }
                }
            }
        }
    }
}
