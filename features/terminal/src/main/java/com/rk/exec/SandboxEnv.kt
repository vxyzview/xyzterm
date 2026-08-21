package com.rk.exec

import android.content.Context
import com.rk.feature.FeatureRegistry
import com.rk.file.localDir
import com.rk.file.localLibDir
import com.rk.file.sandboxHomeDir
import com.rk.settings.Settings
import com.rk.utils.application
import com.rk.utils.getSourceDirOfPackage
import com.rk.utils.getTempDir
import java.io.File
import java.util.TimeZone

/**
 * Single source of truth for the environment handed to proot sessions and
 * one-shot sandbox commands. Previously MkSession and ubuntuProcess each
 * hand-rolled this map and drifted apart over time.
 */
object SandboxEnv {
    fun build(context: Context, prootTmpDir: String): MutableMap<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val env =
            mutableMapOf(
                "PROOT" to "$nativeLibDir/libproot.so",
                "PROOT_LOADER" to "$nativeLibDir/libloader.so",
                "NATIVE_LIB_DIR" to nativeLibDir,
                "LINKER" to
                    {
                        if (File("/system/bin/linker64").exists()) {
                            "/system/bin/linker64"
                        } else {
                            "/system/bin/linker"
                        }
                    },
                "COLORTERM" to "truecolor",
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "DEBUG" to FeatureRegistry.isEnabled("debug_mode").toString(),
                "PUBLIC_HOME" to context.getExternalFilesDir(null)?.absolutePath.orEmpty(),
                "HOME" to
                    {
                        if (Settings.sandbox) {
                            "/home"
                        } else {
                            sandboxHomeDir(context).absolutePath
                        }
                    },
                "PROMPT_DIRTRIM" to "2",
                "SANDBOX" to Settings.sandbox.toString(),
                "LOCAL" to localDir(context).absolutePath,
                "PRIVATE_DIR" to context.filesDir.parentFile!!.absolutePath,
                "EXT_HOME" to sandboxHomeDir(context).absolutePath,
                "LD_LIBRARY_PATH" to localLibDir(context).absolutePath,
                "TMP_DIR" to getTempDir().absolutePath,
                "TMPDIR" to getTempDir().absolutePath,
                "TZ" to TimeZone.getDefault().id,
                "DOTNET_GCHeapHardLimit" to "1C0000000",
                "SOURCE_DIR" to context.applicationInfo.sourceDir,
                "TERMUX_X11_SOURCE_DIR" to getSourceDirOfPackage(application!!, "com.termux.x11").orEmpty(),
                "DISPLAY" to ":0",
            )

        listOf(
                "ANDROID_ART_ROOT",
                "ANDROID_DATA",
                "ANDROID_I18N_ROOT",
                "ANDROID_ROOT",
                "ANDROID_RUNTIME_ROOT",
                "ANDROID_TZDATA_ROOT",
                "BOOTCLASSPATH",
                "DEX2OATBOOTCLASSPATH",
                "EXTERNAL_STORAGE",
            )
            .forEach { env[it] = System.getenv(it).orEmpty() }

        when (Settings.seccomp_mode) {
            "yes" -> env["SECCOMP"] = "1"
            "no" -> env["PROOT_NO_SECCOMP"] = "1"
        }

        val loader32 = "$nativeLibDir/libloader32.so"
        if (File(loader32).exists()) {
            env["PROOT_LOADER_32"] = loader32
        }

        env["PROOT_TMP_DIR"] = prootTmpDir

        return env
    }
}
