package com.rk.settings.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import com.rk.exec.ProotSandboxPaths
import com.rk.exec.ShellUtils
import com.rk.exec.isTerminalInstalled
import com.rk.resources.strings
import com.rk.utils.application
import com.rk.utils.getTempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** Upper bound for every spawned probe; a hung proot must not wedge the screen. */
const val CHECK_TIMEOUT_SECONDS = 15L

/** These checks are intended for troubleshooting terminal issues */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun terminalChecks(): SnapshotStateList<Check> {
    val checkProot = stringResource(strings.check_proot)
    val checkSystemShell = stringResource(strings.check_system_shell)
    val checkStoragePermissions = stringResource(strings.check_storage_permissions)
    val checkUbuntu = stringResource(strings.check_ubuntu)
    val checkNetworkAccess = stringResource(strings.check_network_access)
    val checkAbnormalities = stringResource(strings.check_abnormalities)

    return remember {
        mutableStateListOf(
            Check(
                label = checkProot,
                run = { printLog ->
                    val libproot = File(application!!.applicationInfo.nativeLibraryDir, "libproot.so")
                    val prootloader = File(application!!.applicationInfo.nativeLibraryDir, "libloader.so")
                    val prootloader32 = File(application!!.applicationInfo.nativeLibraryDir, "libloader32.so")

                    printLog("PRoot exists: ${libproot.exists()}")
                    printLog("PRoot readable: ${libproot.canRead()}")
                    printLog("PRoot executable: ${libproot.canExecute()}")
                    printLog("PRoot Loader exists: ${prootloader.exists()}")
                    printLog("32bit PRoot Loader exists: ${prootloader32.exists()}")

                    var exitCode = 999

                    try {
                        printLog("Creating a temporary sandbox environment...")

                        val process =
                            ProcessBuilder(libproot.absolutePath, "-0", "-r", "/", "true")
                                .apply {
                                    environment()["PROOT_TMP_DIR"] = getTempDir().absolutePath
                                    environment()["PROOT_LOADER"] = prootloader.absolutePath
                                    environment()["PROOT_LOADER_32"] = prootloader32.absolutePath
                                }
                                .start()

                        exitCode =
                            if (process.waitFor(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                process.exitValue()
                            } else {
                                process.destroyForcibly()
                                printLog("PRoot timed out after ${CHECK_TIMEOUT_SECONDS}s")
                                124
                            }

                        printLog("Exit code: $exitCode")

                        if (exitCode != 0) {
                            val stderr = process.errorStream.bufferedReader().use { it.readText() }

                            if (stderr.isNotBlank()) {
                                printLog("stderr:")
                                printLog(stderr)
                            }
                        }
                    } catch (e: Exception) {
                        printLog("Error while running PRoot: ${e.message}")
                    }

                    libproot.exists() && exitCode == 0
                },
            ),
            Check(
                label = checkSystemShell,
                run = { printLog ->
                    val shell = File("/system/bin/sh")
                    printLog("$shell exists: ${shell.exists()}")

                    val shell1 = File("/bin/sh")
                    printLog("$shell1 exists: ${shell1.exists()}")

                    printLog("$shell readable: ${shell.canRead()}")
                    printLog("$shell1 readable: ${shell1.canRead()}")

                    printLog("$shell executable: ${shell.canExecute()}")
                    printLog("$shell1 executable: ${shell1.canExecute()}")

                    var exitcode: Int = 999
                    try {
                        exitcode = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "true")).waitFor()
                        printLog("Exit code: $exitcode")
                    } catch (e: Exception) {
                        printLog("Error while running shell: ${e.message}")
                    }

                    // /bin/sh does not exist on most modern Android; only
                    // /system/bin/sh is required for the shell to work.
                    exitcode == 0 && shell.exists() && shell.canRead() && shell.canExecute()
                },
            ),
            Check(
                label = checkStoragePermissions,
                run = { printLog ->
                    val filesDir = application!!.filesDir
                    val totalSpace = filesDir.totalSpace / (1024 * 1024)
                    val freeSpace = filesDir.freeSpace / (1024 * 1024)
                    printLog("Internal Storage - Total: $totalSpace MB, Free: $freeSpace MB")

                    printLog("Files Dir: ${filesDir.absolutePath}")
                    printLog("Files Dir Writable: ${filesDir.canWrite()}")

                    val sandboxHome = ProotSandboxPaths(application!!).sandboxHome
                    printLog("Sandbox Home: ${sandboxHome.absolutePath}")
                    printLog("Sandbox Home Writable: ${sandboxHome.canWrite()}")

                    if (freeSpace < 500) {
                        printLog("Warning: Low storage space (< 500 MB). Ubuntu might fail to install or update.")
                    }

                    freeSpace > 100 && filesDir.canWrite() && sandboxHome.canWrite()
                },
            ),
            Check(
                label = checkUbuntu,
                run = { printLog ->
                    if (!isTerminalInstalled()) {
                        printLog("Ubuntu not installed, skipping")
                        return@Check true
                    }

                    val rootfs = ProotSandboxPaths(application!!).sandboxRoot
                    printLog("RootFS path: ${rootfs.absolutePath}")

                    val bash = rootfs.child("bin/bash")
                    printLog("Bash exists: ${bash.exists()}")
                    if (bash.exists()) {
                        printLog("Bash executable: ${bash.canExecute()}")
                    }

                    val apt = rootfs.child("usr/bin/apt")
                    printLog("Apt exists: ${apt.exists()}")

                    val osRelease = rootfs.child("etc/os-release")
                    if (osRelease.exists()) {
                        printLog("OS Release info:")
                        osRelease.readLines().take(5).forEach { printLog("  $it") }
                    }

                    printLog("Testing Ubuntu execution...")
                    var working = false
                    try {
                        // Route through ShellUtils.runUbuntu so stdout/stderr drain
                        // concurrently (the 64KB pipe wedge the ponytail note had to
                        // hand-fix), with the wait bounded by CHECK_TIMEOUT_SECONDS.
                        val result = ShellUtils.runUbuntu("true", timeoutSeconds = CHECK_TIMEOUT_SECONDS)
                        printLog("Exit code: ${result.exitCode}")
                        if (result.error.isNotEmpty()) printLog("Stderr: ${result.error}")

                        working = result.exitCode == 0 && !result.timedOut
                    } catch (e: Exception) {
                        printLog("Execution failed: ${e.message}")
                    }

                    working
                },
            ),
            Check(
                label = checkNetworkAccess,
                run = { printLog ->
                    if (!isTerminalInstalled()) {
                        printLog("Ubuntu not installed, skipping network check.")
                        return@Check true
                    }

                    printLog("Checking DNS resolution (google.com)...")
                    try {
                        val dns =
                            ShellUtils.runUbuntu(
                                "getent", "hosts", "google.com",
                                timeoutSeconds = CHECK_TIMEOUT_SECONDS,
                            )
                        if (dns.exitCode == 0 && !dns.timedOut) {
                            printLog("DNS resolution works.")
                        } else {
                            printLog("DNS resolution FAILED.")
                            if (dns.timedOut) printLog("Timed out after ${CHECK_TIMEOUT_SECONDS}s")
                            if (dns.error.isNotEmpty()) printLog("Stderr: ${dns.error}")
                            val resolvConf = ProotSandboxPaths(application!!).sandboxRoot.child("etc/resolv.conf")
                            if (resolvConf.exists()) {
                                printLog("/etc/resolv.conf exists, content:")
                                resolvConf.readLines().forEach { printLog("  $it") }
                            } else {
                                printLog("/etc/resolv.conf is MISSING!")
                            }
                            printLog("Abnormality: Ubuntu will not have internet access without DNS.")
                        }
                        dns.exitCode == 0 && !dns.timedOut
                    } catch (e: Exception) {
                        printLog("Network check failed: ${e.message}")
                        false
                    }
                },
            ),
            Check(
                label = checkAbnormalities,
                run = { printLog ->
                    if (!isTerminalInstalled()) {
                        printLog("Ubuntu not installed, skipping")
                        return@Check true
                    }
                    var abnormalities = 0

                    try {
                        val touch =
                            ShellUtils.runUbuntu(
                                "touch", "/tmp/.test_xed",
                                timeoutSeconds = CHECK_TIMEOUT_SECONDS,
                            )
                        if (touch.exitCode == 0 && !touch.timedOut) {
                            ShellUtils.runUbuntu(
                                "rm", "/tmp/.test_xed",
                                timeoutSeconds = CHECK_TIMEOUT_SECONDS,
                            )
                        } else {
                            printLog("Abnormality: /tmp is not writable inside sandbox.")
                            abnormalities++
                        }
                    } catch (e: Exception) {
                        printLog("Error checking /tmp: ${e.message}")
                    }

                    if (abnormalities == 0) {
                        printLog("No major abnormalities detected.")
                    } else {
                        printLog("Found $abnormalities abnormality/ies.")
                    }

                    abnormalities == 0
                },
            ),
        )
    }
}
