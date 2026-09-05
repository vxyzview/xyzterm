package com.rk.exec

import android.annotation.SuppressLint
import android.util.Log
import com.rk.feature.FeatureRegistry
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.settings.Settings
import com.rk.utils.application
import com.rk.utils.getTempDir
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-lifetime scope that reaps per-spawn proot tmp dirs; replaces GlobalScope. */
private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

data class Binding(val outside: String, val inside: String? = null)

private fun MutableList<String>.bind(outside: String, inside: String? = null) {
    if (File(outside).exists()) {
        add("-b")
        add("$outside${if (inside != null){":$inside"}else{""}}")
    }
}

fun List<Binding>.attachTo(list: MutableList<String>, excludeMounts: List<String> = listOf<String>()) {
    forEach {
        if (!excludeMounts.contains(it.outside)) {
            list.bind(it.outside, it.inside)
        }
    }
}

fun getDefaultBindings(): List<Binding> {
    fun MutableList<Binding>.bind(outside: String, inside: String? = null) {
        if (File(outside).exists()) {
            add(Binding(outside, inside))
        }
    }

    val list = mutableListOf<Binding>()

    with(list) {
        bind(sandboxHomeDir().absolutePath, "/home")
        bind("/sdcard")
        bind("/storage")
        // Sandbox mode deliberately does not expose raw /data: the guest reaches
        // its own files through the app-private dir binding below, while other
        // apps' private dirs (and /data/adb on rooted devices) stay invisible.
        // Failsafe (non-sandbox) mode keeps the legacy full-/data binding.
        if (!Settings.sandbox) {
            bind("/data")
        }
        bind(application!!.filesDir.parentFile!!.absolutePath)
        bind("/dev")
        bind("/proc")
        bind("/system")
        bind("/sys")
        bind("/dev/urandom", "/dev/random")
        bind("/system_ext")
        bind("/product")
        bind("/odm")
        bind("/apex")
        bind("/vendor")
        bind("/linkerconfig/ld.config.txt")
        bind("/linkerconfig/com.android.art/ld.config.txt")
        bind("/plat_property_contexts", "/property_contexts")
        bind("/sys")
        bind("${getTempDir().absolutePath}", "/dev/shm")
    }

    // User-defined binds come last: built-in mounts keep priority, and any
    // entry shadowing an existing outside path (or duplicating another user
    // entry) is skipped rather than stacked.
    val bound = list.map { it.outside }.toMutableSet()
    UserBindings.decode(Settings.custom_bindings).forEach { binding ->
        if (bound.add(binding.outside)) {
            list.add(binding)
        }
    }

    return list
}

suspend fun ubuntuProcess(
    excludeMounts: List<String> = listOf(),
    root: File = sandboxDir(),
    workingDir: String? = null,
    command: List<String>,
): Process =
    withContext(Dispatchers.IO) {
        if (!root.exists()) throw NoSuchFileException(root)

        val randomInt = Random.nextInt()
        val tmpDir = getTempDir().child("$randomInt-sandbox")
        // ponytail: exists()+mkdirs() is a TOCTOU; mkdirs() on its own returns
        // true if the dir already existed or was created, false only on real failure.
        if (!tmpDir.mkdirs()) {
            throw IOException("failed to create proot tmp dir: ${tmpDir.absolutePath}")
        }

        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"

        val args =
            mutableListOf<String>().apply {
                add("${application!!.applicationInfo.nativeLibraryDir}/libproot.so")
                add("--kill-on-exit")

                if (workingDir != null) {
                    add("-w")
                    add(workingDir)
                }

                getDefaultBindings().attachTo(this, excludeMounts)

                bind(tmpDir.absolutePath)

                add("-0")
                add("--link2symlink")
                add("--sysvipc")
                add("-L")

                add("-r")
                add(root.absolutePath)
                addAll(command)
            }

        if (FeatureRegistry.isEnabled("debug_mode")) {
            Log.i("SANDBOX", args.toList().toString())
        }

        val processBuilder = ProcessBuilder(linker, *args.toTypedArray())

        processBuilder.environment().let { env ->
            env.putAll(SandboxEnv.build(application!!, tmpDir.absolutePath))

            env["WKDIR"] = workingDir.orEmpty()

            env["PATH"] =
                "/bin:/sbin:/usr/bin:/usr/sbin:/usr/games:/usr/local/bin:/usr/local/sbin:${localBinDir()}:${System.getenv("PATH")}"
        }

        val process = processBuilder.start()

        // The per-invocation PROOT_TMP_DIR binding is never reused; reap it once
        // the process is gone instead of leaking one temp dir per spawn.
        reaperScope.launch {
            runCatching { process.waitFor() }
            tmpDir.deleteRecursively()
        }

        return@withContext process
    }

@SuppressLint("SdCardPath")
suspend fun ubuntuProcess(
    excludeMounts: List<String> = listOf(),
    root: File = sandboxDir(),
    workingDir: String? = null,
    vararg command: String,
): Process {
    return ubuntuProcess(excludeMounts, root, workingDir, command.toMutableList())
}

/** Extension to read all stdout as a single string */
suspend fun Process.readStdout(): String =
    withContext(Dispatchers.IO) {
        try {
            // readText blocks to EOF — an available() pre-check here would
            // return "" whenever the process hasn't flushed yet.
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
            if (e.message?.contains("Stream closed") == true) "" else throw e
        }
    }

/** Extension to write to process stdin */
suspend fun Process.writeInput(input: String, flush: Boolean = true) =
    withContext(Dispatchers.IO) {
        OutputStreamWriter(outputStream).use { writer ->
            writer.write(input)
            if (flush) writer.flush()
        }
    }

/** Extension to wait for process to finish and return exit code */
suspend fun Process.awaitExit(): Int = withContext(Dispatchers.IO) { waitFor() }

/** Extension to destroy process safely */
fun Process.terminate() {
    if (isAlive) destroy()
}

/** Extension to check if process is alive */
fun Process.isRunning(): Boolean = isAlive
