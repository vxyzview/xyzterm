import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "com.xyzterm.terminal"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

// Bundle the pinned archm rootfs into the APK so first launch never downloads
// it. The task lives in buildSrc so it stays compatible with the configuration
// cache; Constants.kt remains the single source of truth for the digests.
private val ARCHM_TAG = "2026.11.21"
private val ARCHM_BASE = "https://github.com/vxyzview/archm/releases/download/$ARCHM_TAG"
private val ARCHM_SHA256: Map<String, String> =
    Regex("urlName = \"archm-(arm64|arm)\\.tar\\.gz\",[\\s\\S]*?sha256 = \"([0-9a-f]{64})\"")
        .findAll(File(rootDir, "features/terminal/src/main/java/com/xyzterm/terminal/Constants.kt").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

val downloadRootfs by
    tasks.registering(DownloadRootfsTask::class) {
        group = "build"
        description = "Downloads and verifies the archm rootfs tarballs into the app assets."
        baseUrl.set(ARCHM_BASE)
        digests.set(ARCHM_SHA256)
        assetsDir.set(layout.projectDirectory.dir("src/main/assets/terminal"))
    }

tasks.named("preBuild") { dependsOn(downloadRootfs) }

dependencies {
    implementation(project(":core:main"))
    implementation(project(":core:components"))
    implementation(project(":core:resources"))
    implementation(project(":features:terminal:proot"))
    implementation(project(":features:terminal:link2symlink"))

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.gson)
    implementation(libs.semver)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)

    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")

    testImplementation(libs.junit)
}
