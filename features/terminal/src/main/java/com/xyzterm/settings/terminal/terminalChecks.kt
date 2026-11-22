package com.xyzterm.settings.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import com.xyzterm.exec.ShellUtils
import com.xyzterm.exec.isTerminalInstalled
import com.xyzterm.file.child
import com.xyzterm.file.sandboxDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.resources.strings
import com.xyzterm.utils.application
import java.io.File

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
                    printLog("PRoot Loader exists: ${prootloader.exists()}")
                    printLog("32bit PRoot Loader exists: ${prootloader32.exists()}")

                    // Use the same linker-based launch path as real sessions.
                    val result =
                        try {
                            ShellUtils.runUbuntu("true", timeoutSeconds = CHECK_TIMEOUT_SECONDS)
                        } catch (e: Exception) {
                            printLog("Error while running PRoot: ${e.message}")
                            null
                        }
                    if (result != null) {
                        printLog("Exit code: ${result.exitCode}")
                        if (result.error.isNotBlank()) printLog("stderr: ${result.error}")
                    }
                    libproot.exists() && result?.exitCode == 0 && result.timedOut.not()
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

                    val sandboxHome = sandboxHomeDir()
                    printLog("Sandbox Home: ${sandboxHome.absolutePath}")
                    printLog("Sandbox Home Writable: ${sandboxHome.canWrite()}")

                    if (freeSpace < 500) {
                        printLog("Warning: Low storage space (< 500 MB). archm might fail to install or update.")
                    }

                    freeSpace > 100 && filesDir.canWrite() && sandboxHome.canWrite()
                },
            ),
            Check(
                label = checkUbuntu,
                run = { printLog ->
                    if (!isTerminalInstalled()) {
                        printLog("archm not installed, skipping")
                        return@Check true
                    }

                    val rootfs = sandboxDir()
                    printLog("RootFS path: ${rootfs.absolutePath}")

                    val bash = rootfs.child("bin/bash")
                    printLog("Bash exists: ${bash.exists()}")
                    if (bash.exists()) {
                        printLog("Bash executable: ${bash.canExecute()}")
                    }

                    val pacman = rootfs.child("usr/bin/pacman")
                    printLog("Pacman exists: ${pacman.exists()}")

                    val osRelease = rootfs.child("etc/os-release")
                    if (osRelease.exists()) {
                        printLog("OS Release info:")
                        osRelease.readLines().take(5).forEach { printLog("  $it") }
                    }

                    printLog("Testing archm execution...")
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
                        printLog("archm not installed, skipping network check.")
                        return@Check true
                    }

                    printLog("Checking DNS resolution (google.com)...")
                    try {
                        val dns =
                            ShellUtils.runUbuntu(
                                "getent",
                                "hosts",
                                "google.com",
                                timeoutSeconds = CHECK_TIMEOUT_SECONDS,
                            )
                        if (dns.exitCode == 0 && !dns.timedOut) {
                            printLog("DNS resolution works.")
                        } else {
                            printLog("DNS resolution FAILED.")
                            if (dns.timedOut) printLog("Timed out after ${CHECK_TIMEOUT_SECONDS}s")
                            if (dns.error.isNotEmpty()) printLog("Stderr: ${dns.error}")
                            val resolvConf = sandboxDir().child("etc/resolv.conf")
                            if (resolvConf.exists()) {
                                printLog("/etc/resolv.conf exists, content:")
                                resolvConf.readLines().forEach { printLog("  $it") }
                            } else {
                                printLog("/etc/resolv.conf is MISSING!")
                            }
                            printLog("Abnormality: archm will not have internet access without DNS.")
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
                        printLog("archm not installed, skipping")
                        return@Check true
                    }
                    var abnormalities = 0

                    try {
                        val touch =
                            ShellUtils.runUbuntu(
                                "touch",
                                "/tmp/.test_xed",
                                timeoutSeconds = CHECK_TIMEOUT_SECONDS,
                            )
                        if (touch.exitCode == 0 && !touch.timedOut) {
                            ShellUtils.runUbuntu(
                                "rm",
                                "/tmp/.test_xed",
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
