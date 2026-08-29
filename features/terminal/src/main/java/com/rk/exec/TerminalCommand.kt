package com.rk.exec

/**
 * A one-shot command destined for the next session created by
 * [com.rk.terminal.MkSessionFactory]. Constructed by external callers
 * (TerminalLauncher handler, launchTerminal), consumed inside
 * [com.rk.terminal.MkSessionFactory.create] via [setPendingCommand].
 *
 * Previously exposed as `var pendingCommand: TerminalCommand? = null`
 * at file scope; the global was lifted into the factory, where the
 * one-write-one-read invariant actually lives.
 */
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