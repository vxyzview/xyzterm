# Port rules

Behavior the port must keep exactly, learned from corrections. Every agent prompt rust-anything writes includes these rules. Edit them by hand, or add one with `rust-anything rules --add`.

- Port keeps behavior exactly, bugs included; a mismatch is a bug in the port, never in the recording.
- No unsafe; no unwrap, expect or panic! where the original throws — return Err with the original's exception class.
- Kotlin String offsets are UTF-16 code units; Rust is UTF-8 bytes — use src/utf16.rs for offsets.
- Kotlin Char.isISOControl and isNotBlank use JVM Unicode sets; match them exactly, never Rust char::is_whitespace.
- Byte-exact formats and exception classes; never widen a tolerance, weaken a test or edit cases.jsonl.
- GPL-3.0-or-later-compatible deps only; no telemetry or user-uninitiated network calls.
