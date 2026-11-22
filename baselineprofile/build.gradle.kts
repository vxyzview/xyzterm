plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.baselineprofile)
}

android {
    namespace = "com.xyzterm.app.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // CI starts an emulator (see .github/workflows/baseline-profile.yml) and
    // the plugin drives it as a connected device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.runner)
}
