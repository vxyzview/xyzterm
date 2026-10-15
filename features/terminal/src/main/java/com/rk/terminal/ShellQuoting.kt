package com.rk.terminal

/**
 * POSIX single-quoting for a command line that must survive one hop through `sh -c`.
 *
 * A sandboxed command reaches `sandbox.sh` as a single string, which `sandbox.sh` then hands to
 * `bash -c "$*"`. `bash -c` accepts exactly one string, so the argv has to be re-encoded
 * somewhere — and re-encoding without quoting turns
 *
 *     exe = "ls", args = ["/tmp; rm -rf ~"]
 *
 * into the shell source `ls /tmp; rm -rf ~`.
 *
 * Single quotes are the only quoting form that is inert for `$`, backticks, `\`, `;`, `|` and
 * `&` alike. The embedded-single-quote case uses the standard close-escape-reopen dance.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Joins [parts] into a single command line, quoting each part. */
internal fun shellCommandLine(parts: Array<String>): String =
    parts.joinToString(" ") { shellQuote(it) }