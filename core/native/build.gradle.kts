plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
}

val rustJava = layout.buildDirectory.dir("generated/rust/java")
val copyRustJava =
    tasks.register<Sync>("copyRustJava") {
        from(rootProject.file("rust/validation/java/rustanything"))
        into(rustJava.map { it.dir("rustanything") })
    }

android {
    namespace = "com.xyzterm.rustnative"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            // Only the rustanything classes are compiled: the generated facade
            // wrappers would collide with the Kotlin facades rust-anything records from.
            java.srcDir(rustJava)
            jniLibs.srcDir(rootProject.file("rust/validation/jniLibs"))
        }
    }
}

val rustDir = rootProject.file("rust")
val ndkHome =
    providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.environmentVariable("ANDROID_HOME").map { "$it/ndk/30.0.16248370" })

listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android26-clang"),
    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi26-clang"),
).forEach { (abi, target, clang) ->
    val suffix = abi.replace('-', '_')
    val soFile = rustDir.resolve("validation/jniLibs/$abi/libvalidation.so")
    val build =
        tasks.register<Exec>("buildValidation$suffix") {
            workingDir(rustDir)
            inputs.dir(rustDir.resolve("validation/src"))
            inputs.file(rustDir.resolve("validation/Cargo.toml"))
            inputs.file(rustDir.resolve("validation/Cargo.lock"))
            outputs.file(soFile)
            environment("CARGO_INCREMENTAL", "0")
            environment("RUSTFLAGS", "--remap-path-prefix=${rustDir.absolutePath}=. -C strip=symbols")
            environment(
                "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER",
                ndkHome.map { "$it/toolchains/llvm/prebuilt/linux-x86_64/bin/$clang" },
            )
            commandLine(
                "cargo",
                "build",
                "--manifest-path",
                "validation/Cargo.toml",
                "--release",
                "--locked",
                "--target",
                target,
            )
        }
    tasks.register<Copy>("installValidation$suffix") {
        dependsOn(build)
        from(rustDir.resolve("validation/target/$target/release/libvalidation.so"))
        into(soFile.parentFile)
    }
}

tasks.named("preBuild") {
    dependsOn(
        copyRustJava,
        tasks.named("installValidationarm64_v8a"),
        tasks.named("installValidationarmeabi_v7a"),
    )
}
