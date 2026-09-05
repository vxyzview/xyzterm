plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.benchmark)
}

android {
    namespace = "com.xyzterm.app.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.benchmark.macro.junit4.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
}
