plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.baselineprofile)
}

android {
    namespace = "com.xyzterm.app.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.benchmark.macro.junit4.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // Release-only: benchmarks measure the shipped (minified + profiled)
    // build; the debuggable debug variant is meaningless to measure.
    buildTypes {
        release {}
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
}
