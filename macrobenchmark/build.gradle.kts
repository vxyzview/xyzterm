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
}
