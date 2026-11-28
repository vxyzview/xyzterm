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

// Pin one immutable archm release so the tarball and checksum can never come
// from different versions, and repeat builds can reuse the extracted assets.
private val ARCHM_BASE = "https://github.com/vxyzview/archm/releases/download/2026.11.23"

val downloadRootfs =
    tasks.register<DownloadRootfsTask>("downloadRootfs") {
        group = "build"
        description = "Downloads and verifies the archm rootfs tarballs into the app assets."
        baseUrl.set(ARCHM_BASE)
        stagingDir.set(layout.buildDirectory.dir("rootfs"))
        armAssetsDir.set(rootProject.layout.projectDirectory.dir("app/src/arm/assets/terminal"))
        arm64AssetsDir.set(rootProject.layout.projectDirectory.dir("app/src/arm64/assets/terminal"))
    }

tasks.named("preBuild") { dependsOn(downloadRootfs) }

dependencies {
    implementation(project(":core:main"))
    implementation(project(":core:components"))
    implementation(project(":core:logic"))
    implementation(project(":core:resources"))
    implementation(project(":features:terminal:proot"))
    implementation(project(":features:terminal:link2symlink"))

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.documentfile)
    implementation(libs.gson)

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
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.runner)
}
