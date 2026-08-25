package com.rk.exec

import java.util.concurrent.atomic.AtomicReference

private val pendingRef = AtomicReference<TerminalCommand?>(null)

var pendingCommand: TerminalCommand?
    get() = pendingRef.get()
    set(value) {
        pendingRef.set(value)
    }

/**
 * Atomically clears and returns the pending launch command.
 *
 * Session creation runs concurrently (parallel restore, drawer, external
 * launches), so read-then-clear sequences on a plain var raced: every restored
 * shell could observe and re-execute the same command, or a clear between two
 * reads threw NPEs. Consume exactly once via this function instead.
 */
fun consumePendingCommand(): TerminalCommand? = pendingRef.getAndSet(null)

data class TerminalCommand(
    val sandbox: Boolean = true,
    val exe: String,
    val args: Array<String> = arrayOf(),
    val id: String,
    val terminatePreviousSession: Boolean = true,
    val workingDir: String? = null,
    val env: Array<String> = arrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (javaClass != other?.javaClass) return false

        other as TerminalCommand

        if (exe != other.exe) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = exe.hashCode()
        result = 31 * result + args.contentHashCode()
        return result
    }
}
