package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import com.rk.exec.TerminalCommand
import org.junit.Test

/**
 * Pins the argv a session is launched with.
 *
 * The command path was `ProcessBuilder("/system/bin/sh", "-c", sandbox.sh, exe, *args)`.
 *
 * Two things were wrong with that:
 *
 *  1. **The shell short-circuits `-c <path>`.** When the `-c` operand names an existing file,
 *     Android's `sh` (mksh) and dash both *run the file* rather than treat it as command text,
 *     and discard the operands after it. So `exe` never became `$0` and never reached the shell.
 *  2. **The executable was dropped anyway.** Even under the POSIX reading, `sh -c script name a b`
 *     puts `name` in `$0`, and sandbox.sh ended at `bash -i -c "$*"` — which excludes `$0`. Only
 *     `args` ever ran.
 *
 * The unsandboxed path had the mirror bug: it produced `ProcessBuilder(exe, "-c", *args)`, handing
 * the binary a `-c` flag it never asked for.
 *
 * The production change these tests catch: reintroducing `-c <scriptPath>` before operands.
 */
class SessionLaunchArgsTest {

    private val sandboxSh = "/data/sandbox"
    private val setupSh = "/data/setup"

    private fun command(
        isExtraction: Boolean = false,
        sandboxEnabled: Boolean = true,
        pending: TerminalCommand? = null,
    ): Pair<String, Array<String>> =
        buildSessionCommand(isExtraction, sandboxEnabled, sandboxSh, setupSh, pending)

    @Test
    fun `an interactive sandboxed shell launches the script with no operands`() {
        val (shell, args) = command()

        assertThat(shell).isEqualTo("/system/bin/sh")
        // Exactly the script, nothing else: sandbox.sh opens an interactive shell when $# == 0.
        assertThat(args.asList()).containsExactly(sandboxSh)
    }

    @Test
    fun `an interactive shell never passes -c`() {
        val (_, args) = command()

        assertThat(args.asList()).doesNotContain("-c")
    }

    @Test
    fun `a sandboxed command passes one quoted command line as a single parameter`() {
        val pending = TerminalCommand(sandbox = true, exe = "ls", args = arrayOf("-l", "/tmp"), id = "x")

        val (shell, args) = command(pending = pending)

        assertThat(shell).isEqualTo("/system/bin/sh")
        assertThat(args).hasLength(2)
        assertThat(args[0]).isEqualTo(sandboxSh)
        assertThat(args[1]).isEqualTo("'ls' '-l' '/tmp'")
    }

    @Test
    fun `a sandboxed command never puts the script behind -c`() {
        val pending = TerminalCommand(sandbox = true, exe = "echo", id = "x")

        val (_, args) = command(pending = pending)

        assertThat(args.asList()).doesNotContain("-c")
        assertThat(args.first()).isEqualTo(sandboxSh)
    }

    @Test
    fun `a shell metacharacter in an argument cannot escape into the command line`() {
        val pending =
            TerminalCommand(sandbox = true, exe = "echo", args = arrayOf("/tmp; rm -rf ~"), id = "x")

        val (_, args) = command(pending = pending)

        assertThat(args[1]).isEqualTo("'echo' '/tmp; rm -rf ~'")
    }

    @Test
    fun `an unsandboxed command execs the binary directly with its own argv`() {
        val pending =
            TerminalCommand(sandbox = false, exe = "/system/bin/ls", args = arrayOf("-la"), id = "x")

        val (shell, args) = command(pending = pending)

        assertThat(shell).isEqualTo("/system/bin/ls")
        // The old shape handed the binary a `-c` flag it never asked for.
        assertThat(args.asList()).containsExactly("-la").inOrder()
        assertThat(args.asList()).doesNotContain("-c")
    }

    @Test
    fun `an unsandboxed command with no arguments still execs the binary`() {
        val pending = TerminalCommand(sandbox = false, exe = "/system/bin/id", id = "x")

        val (shell, args) = command(pending = pending)

        assertThat(shell).isEqualTo("/system/bin/id")
        assertThat(args).isEmpty()
    }

    @Test
    fun `the extraction path keeps its existing -c setup invocation`() {
        val (shell, args) = command(isExtraction = true)

        assertThat(shell).isEqualTo("/system/bin/sh")
        assertThat(args.first()).isEqualTo("-c")
        assertThat(args[1]).isEqualTo(setupSh)
    }
}