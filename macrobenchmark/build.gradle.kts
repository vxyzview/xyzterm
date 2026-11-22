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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator numbers aren't device-representative, but they are stable
        // run-over-run on the same runner image: valid for comparisons.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    // Plain com.android.test modules only create a debug test variant;
    // create release so connectedReleaseAndroidTest measures the shipped
    // (minified + profiled) build. Unlike Groovy's buildTypes { release {} }
    // (which creates), Kotlin resolves — so create() explicitly.
    buildTypes {
        create("release") {}
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.runner)
}
