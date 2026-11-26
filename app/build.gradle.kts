import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.baselineprofile)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "com.xyzterm.app"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.xyzterm.app"
        minSdk = 26

        targetSdk = 37

        // versioning
        versionCode = 13
        versionName = "1.0.8"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        viewBinding = true
        compose = false
        buildConfig = true
        resValues = true
    }

    // One APK per ABI: each ships only its own rootfs instead of both (~430 MB total).
    flavorDimensions += "abi"
    productFlavors {
        create("arm") {
            dimension = "abi"
            versionCode = 13
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("arm64") {
            dimension = "abi"
            versionCode = 14
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    sourceSets {
        getByName("arm") { assets.directories.add(file("src/arm/assets")) }
        getByName("arm64") { assets.directories.add(file("src/arm64/assets")) }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        create("release") {
            val isGitHubActions = System.getenv("GITHUB_ACTIONS") == "true"

            val propertiesFilePath =
                if (isGitHubActions) {
                    "/tmp/signing.properties"
                } else {
                    "signing.properties"
                }

            val propertiesFile = File(propertiesFilePath)
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(propertiesFile.inputStream())
                keyAlias = properties["keyAlias"] as String?
                keyPassword = properties["keyPassword"] as String?
                storeFile =
                    if (isGitHubActions) {
                        File("/tmp/xyzterm.keystore")
                    } else {
                        (properties["storeFile"] as String?)?.let { File(it) }
                    }

                storePassword = properties["storePassword"] as String?
            } else {
                println("Signing properties file not found at $propertiesFilePath")
            }
        }
        getByName("debug") {
            storeFile = file(layout.buildDirectory.dir("../testkey.keystore"))
            storePassword = "testkey"
            keyAlias = "testkey"
            keyPassword = "testkey"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "xyzterm-Debug")
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.profileinstaller)
    coreLibraryDesugaring(libs.desugar)

    implementation(project(":core:main"))
    implementation(project(":core:resources"))
    implementation(libs.androidx.appcompat)
    implementation(project(":features:terminal"))
    baselineProfile(project(":baselineprofile"))
}
