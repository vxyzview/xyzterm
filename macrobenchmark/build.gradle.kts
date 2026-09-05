import com.android.build.api.variant.VariantBuilder

plugins {
    alias(libs.plugins.android.test)
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

// Create a release build type and make sure it's the only one enabled:
// only the shipped build is worth measuring.
androidComponents {
    beforeVariants(selector().all()) { variantBuilder: VariantBuilder ->
        variantBuilder.enabled = variantBuilder.buildType == "release"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
}
