import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

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

    androidResources { noCompress += "tar.gz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

// Bundle the pinned archm rootfs into the APK so first launch never downloads
// it. Assets are fetched at build time and verified; git never stores the
// ~200 MB binaries. Constants.kt is the single source of truth for the digests.
private val ARCHM_TAG = "2026.11.21"
private val ARCHM_BASE = "https://github.com/vxyzview/archm/releases/download/$ARCHM_TAG"
private val ARCHM_SHA256: Map<String, String> =
    Regex("name = \"archm-(arm64|arm)\\.tar\\.gz\",[\\s\\S]*?sha256 = \"([0-9a-f]{64})\"")
        .findAll(File(rootDir, "features/terminal/src/main/java/com/xyzterm/terminal/Constants.kt").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadRootfs by tasks.registering {
    group = "build"
    description = "Downloads and verifies the archm rootfs tarballs into the app assets."
    val abis = listOf("arm64", "arm")
    val assetsDir = layout.projectDirectory.dir("src/main/assets/terminal").asFile
    inputs.property("archmTag", ARCHM_TAG)
    doLast {
        for (abi in abis) {
            val name = "archm-$abi.tar.gz"
            val expected = ARCHM_SHA256[abi] ?: error("No pinned SHA-256 for $name in Constants.kt")
            val target = File(assetsDir, name)
            if (target.exists() && sha256(target) == expected) continue
            target.parentFile.mkdirs()
            val partial = File(target.parentFile, "$name.part")
            URL("$ARCHM_BASE/$name").openStream().use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            val actual = sha256(partial)
            check(actual == expected) { "SHA-256 mismatch for $name: expected $expected, got $actual" }
            check(partial.renameTo(target)) { "Failed to move $name into assets" }
        }
    }
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
