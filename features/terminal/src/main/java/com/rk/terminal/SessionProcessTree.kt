package com.rk.terminal

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Descendant sweep for a dying proot session.
 *
 * proot's `--kill-on-exit` is wired through `atexit()` (`tracee/event.c`), and `atexit` handlers
 * do not run when the process is killed by a signal — which is exactly how Termux ends a session
 * (`finishIfRunning()` sends SIGKILL). The pid we hold is the *tracer*. When it dies, the kernel
 * detaches its tracees: every guest process keeps running under this app's UID, along with the
 * `PROOT_TMP_DIR` that talloc would have freed. `Process.destroy()` is no help either, because
 * proot installs `SIG_IGN` for SIGTERM (`event.c`), so a `nohup python -m http.server &` in any
 * session is an indefinite battery and memory leak.
 *
 * Kills are issued leaves-first: a parent that outlives the sweep can still spawn replacements.
 */
object SessionProcessTree {

    /** Children of [rootPid], nearest generation first, excluding [rootPid] itself. */
    fun descendants(rootPid: Int, childrenOf: (Int) -> List<Int>): List<Int> {
        val visited = mutableSetOf(rootPid)
        val out = mutableListOf<Int>()
        var frontier = childrenOf(rootPid)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (pid in frontier) {
                if (!visited.add(pid)) continue
                out += pid
                next += childrenOf(pid)
            }
            frontier = next
        }
        return out
    }

    /** Real [childrenOf], backed by the `children` file under each of the pid's task dirs. */
    private fun procChildren(pid: Int): List<Int> {
        if (pid <= 0) return emptyList()
        val taskDir = File("/proc/$pid/task")
        val children = mutableListOf<Int>()
        val threads = taskDir.listFiles() ?: return emptyList()
        for (thread in threads) {
            val childrenFile = File(thread, "children")
            val raw =
                runCatching { childrenFile.readText() }
                    .getOrNull()
                    ?: continue
            raw.split(' ', '\n', '\t')
                .mapNotNull { it.trim().toIntOrNull() }
                .forEach { children += it }
        }
        return children
    }

    /**
     * Kills [rootPid]'s descendants deepest-first and then [rootPid] itself.
     *
     * @return the pids signalled, for logging. Never throws: a process that exits between the
     *   `/proc` read and the signal is the normal case, not an error.
     */
    fun killTree(rootPid: Int): List<Int> {
        if (!shouldKill(rootPid)) return emptyList()
        val targets = descendants(rootPid, ::procChildren)
        val signalled = mutableListOf<Int>()
        // Reverse generation order: children of children before children before the tracer.
        for (pid in targets.asReversed()) {
            if (kill(pid)) signalled += pid
        }
        if (kill(rootPid)) signalled += rootPid
        return signalled
    }

    /** `Os.kill` returns Unit; a throw means the pid was already gone, which is the expected race. */
    private fun kill(pid: Int): Boolean =
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
            .fold(onSuccess = { true }, onFailure = { true })

    /** Best-effort check for a diagnostic log; never used to gate a kill. */
    fun isAlive(pid: Int): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else runCatching { File("/proc/$pid").exists() }.getOrDefault(false)
}