# rust/GLOSSARY.md

Terms used in `DECISIONS.md`, `MAP.md` and gate reports.

- **unit** — a package-level group emitted by `rust-anything analyze`; status is `ready`, `caution`, `blocked` or `keep`.
- **start_here** — analyze's best-first port order. Empty for Kotlin and Java here; the four C entries are all readiness 0.
- **bridgeable** — whether `bridge` can emit a crate for a unit. Kotlin's three `ready` units have `bridgeable = 0`; Java's three units all have `bridgeable = 0`.
- **seam** — a behavior-preserving Kotlin/JVM reshape (Phase 2) that makes logic bridgeable: public top-level `fun`, allowed scalar/`List`/nullable types, environment passed in as parameters.
- **bridge** — `rust-anything bridge <repo> <unit-id> --name <crate>`: emits `rust/<crate>` (edition 2024, `rust-version` 1.97, `jni` 0.21, `serde_json`) plus Java wrappers named after the Kotlin file facades, e.g. `com.xyzterm.terminal.SessionNamesKt`.
- **record** — capture the original's behavior into `cases.jsonl`. No `--end-state` flag exists on `bridge` in the installed CLI (ADR-005).
- **diff** — compare `src/port.rs` against `cases.jsonl`; must be N/N. Mismatches are port bugs; messages are ignored, exception classes are not.
- **bench** — measured original-vs-Rust verdict, including bridge cost.
- **shadow** — drop-in with the original API; `RUST_ANYTHING_MODE=shadow|new|old` switches between original, Rust and off.
- **gate** — final per-unit check (fmt, clippy, tests, no `unsafe`/`unwrap`/`panic!` on error paths, all cases match). Port is not done until it passes.
- **bindings** — end state for every unit (ADR-005): Kotlin callers keep calling generated wrappers.
- **known-diffs.jsonl** — one line per operator-approved intentional behavior difference; `{"fn", "args", "actual", "reason"}` copied from `diff --json`.
- **pilot** — `SessionNames.kt` and `DeepLinkCommand.kt`; proves the pipeline including CI and an R8 release build before parallel waves.
- **wave** — analyze's dependency order; units in one wave may go to parallel subagents after the pilot passes.
- **ENC1 / ENC2** — backup archive formats: ENC1 has 120,000 PBKDF2 iterations and no iteration field; ENC2 has 600,000 and a 4-byte big-endian count.
- **custom bind** — guest target / host path pair; first-wins by guest target, so insertion order is behavior.
- **rootfs manifest** — TSV parsed by `RootfsInstaller` with `lineSequence()` (splits on a lone `\r`).
- **SAF** — Storage Access Framework, implemented by `DocumentProvider`; stays Kotlin.
- **Keystore** — `AutoBackupPassphrase`; stays Kotlin.
- **proot / loader** — C executables shipped as `libproot.so` / `libloader.so`, run through the system linker; stay in C (ADR-002).
- **rust-port** — working branch (ADR-007); never push WIP to `main`.
