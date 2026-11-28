# rust/DECISIONS.md

One ADR per G1 decision. Accepted by the operator on 2026-11-28 ("yep" to Q1–Q12).
Base commit `ec937f27c` (`main` = `rust-port`; `versionName = "1.0.9"` at `app/build.gradle.kts:22`).

## ADR-001 — Rust core, Kotlin shell (Q1)
- **Context:** 154 Kotlin files / 17,022 LOC, 9 Java files, ~40k C lines (proot), one C++ file. No JNI in the repo. The terminal emulator and view come from JitPack.
- **Decision:** Kotlin keeps the shell — Compose UI, Activities, Services, SAF, Keystore, `core/components`, `baselineprofile`, `macrobenchmark`, `buildSrc`. Rust takes pure non-UI, non-framework logic, one crate per unit, bridged over JNI.
- **Consequences:** JNI is a new boundary. proot and loader keep running as executables through the system linker (`features/terminal/src/main/java/com/xyzterm/exec/ubuntuProcess.kt:111`, `app/build.gradle.kts:62`).

## ADR-002 — proot stays in C (Q2)
- **Context:** 69 `.c` / 93 `.h`, ~40k lines. `analyze --lang c` marks `syscall/`, `tracee/`, `talloc/` as `keep`; the four `start_here` units score readiness 0 with 180–263 pointer risks and 39 files fail to parse.
- **Decision:** keep `features/terminal/proot` in C; CMake unchanged. Porting leaf helpers or reimplementing proot in Rust requires a separate ADR.
- **Consequences:** no rust-anything C units are in the default port scope.

## ADR-003 — `link2symlink.cpp` stays in C++ (Q3)
- **Context:** one 153-line file; rust-anything does not support C++, so it cannot record or gate a port.
- **Decision:** keep in C++.
- **Consequences:** a hand-port would be outside the gate; it needs its own ADR and differential harness.

## ADR-004 — Termux emulator and view stay external (Q4)
- **Context:** `terminal-emulator` and `terminal-view` v0.118.3 come from JitPack (`features/terminal/build.gradle.kts:66-67`); their source is not in the repo.
- **Decision:** keep as an external dependency. A Rust VT core (`alacritty_terminal`, `vte`) is a rewrite, not a port: only with separate approval and a byte-stream-to-screen-snapshot differential harness.
- **Consequences:** no VT work in the default scope.

## ADR-005 — End state is `bindings` (Q5)
- **Context:** the brief assumed `--end-state` did not exist (5.0.1); installed 5.0.2 `bridge --help` lists `--end-state` with `replace|bindings|scaffold`.
- **Decision:** pass `--end-state bindings` at bridge time: generated wrappers become the public API that Kotlin callers keep importing.
- **Consequences:** the cut-over mechanism is settled in ADR-013 during the pilot.

## ADR-006 — Repo rules vs tool (Q6)
- **Context:** AGENTS.md requires commit + push after finished work, bans committing `.md` and skill/plugin folders, and bans local `./gradlew` builds. `rust-anything doctor` reports the OpenCode skill as stale; this harness is OpenCode, not Claude Code. `.gitignore` ignores only `Todo.md`, `/PRODUCT.md`, `/DESIGN.md` (lines 20, 43, 44).
- **Decision:** local `cargo`, `kotlinc` and `rust-anything` are allowed; `./gradlew` stays banned. Add an explicit AGENTS.md exception allowing `rust/**/*.md` to be committed so DECISIONS/MAP/GLOSSARY/HANDOFF/RULES and generated crate READMEs survive a fresh clone. Use the installed CLI 5.0.2 — its analyze counts match the 5.0.1 measurements — and treat `--help` as authoritative. Run `rust-anything setup --agent opencode` to clear the stale-skill flag; `--agent claude` does not apply to this harness.
- **Consequences:** no `ktfmtFormat`, no `.githooks/pre-commit`, no secrets touched, no telemetry or user-uninitiated network calls (`README.md:50,96`), GPL-3.0-or-later-compatible dependencies only, minimal `cargo tree`.

## ADR-007 — Branching (Q7)
- **Context:** `origin` is `vxyzview/xyzterm` and exposes only `main`; `gh` defaults to upstream `Xed-Editor/Xed-Editor`; there is no `dev` branch. `rust-port` already exists locally at `ec937f27c`. Pushes to `main` trigger signed release and PixelDrain upload (`build-pixeldrain.yml`).
- **Decision:** work on `rust-port`; open a draft PR into `main` (not `dev`); never push WIP to `main`.
- **Consequences:** the draft PR is what exercises `pr-check.yml` (`assembleDebug test`).

## ADR-008 — Equivalence bar (Q8)
- **Context:** exact on-disk and wire formats: `XYZTERM_ENC1`, `XYZTERM_ENC2`, rootfs manifest TSV, custom-binds JSON, keybindings JSON, extra-keys JSON.
- **Decision:** byte-exact for all of them; compare errors by exception class only (`diff` ignores messages). A mismatch is a bug in the port, never in the recording. Never weaken a test, widen a float tolerance or edit `cases.jsonl` to get green.
- **Consequences:** a behavior fix is its own commit, listed in `known-diffs.jsonl`, and needs explicit operator yes.

## ADR-009 — Packaging (Q9)
- **Context:** CI, Docker and F-Droid all enter through `./gradlew`; `.gitignore` blocks `.so`; `useLegacyPackaging = true` at `app/build.gradle.kts:62` and per-flavor `abiFilters` at `:34-62`.
- **Decision:** one cdylib per unit until APK size and cold start are measured; a single workspace `rust/Cargo.toml` with a committed `Cargo.lock`, built `--locked` (fall back to one lock per crate if workspace `build`/`gate` fail); ABIs arm64-v8a and armeabi-v7a only.
- **Consequences:** an Android library module owns `jniLibs/` and builds from source in a `preBuild` task (precedent `downloadRootfs` at `features/terminal/build.gradle.kts:43`); no `.so` is committed; R8 keep rules needed for `rustanything.**` and named exception classes.

## ADR-010 — Reproducibility and F-Droid (Q10)
- **Context:** `reproducible.yml` seds `gradle/gradle-daemon-jvm.properties`, `settings.gradle.kts` and `app/build.gradle.kts` at lines 208–210 and reads the NDK version from `features/terminal/link2symlink/build.gradle.kts:47`. Local JDK is 25.0.5.1 and NDK is 28.0.13004108; CI pins JDK 21 at `.github/workflows/pr-check.yml:31-35` and needs NDK 30.0.16248370.
- **Decision:** pin `rust-toolchain.toml` equal to the crate's `rust-version`; build with `--locked`, `--remap-path-prefix`, `-C strip=symbols`, `CARGO_INCREMENTAL=0`. Install NDK `30.0.16248370` locally and add the pinned Rust toolchain, Android targets and NDK to every workflow and the Dockerfile that calls `./gradlew` (`pr-check.yml`, `android.yml`, `build-pixeldrain.yml`, `baseline-profile.yml`, `macrobenchmark.yml`, `reproducible.yml`, `Dockerfile`), without breaking the sed assumptions. Keep `ktfmt-check.yml` free of Rust in Gradle's configuration phase. Draft the F-Droid metadata change but do not commit it.
- **Consequences:** report the two SHA-256 values from `reproducible.yml`, plus APK size and cold-start deltas per flavor.

## ADR-011 — Device checks (Q11)
- **Context:** `adb devices` reports no attached device.
- **Decision:** the agent does not attach or configure a device; it hands the operator the exact `diff --profile android --abi arm64-v8a --abi armeabi-v7a --api 26 --ndk <path>` command per unit.
- **Consequences:** android-profile diff evidence is produced by the operator.

## ADR-012 — CI baseline (Q12)
- **Context:** `pr-check.yml` exists at `.github/workflows/pr-check.yml:1-43` but has no runs on origin. The latest tag run `46082516440` and main push `46082448517` are green.
- **Decision:** accept those green runs as the pre-change baseline; the first draft PR's `pr-check.yml` run is the real compile gate.
- **Consequences:** no pr-check evidence exists until the draft PR is opened.

## ADR-013 — Pilot cut-over mechanism (G3, revised at G4)
- **Context:** `bridge` emits `java/com/xyzterm/logic/SessionNamesKt.java` and `DeepLinkCommandKt.java` with the same fully qualified names as the Kotlin file facades in `:core:logic`; compiling both collides. Renaming the Kotlin facades with `@file:JvmName` was tried and reverted: `record` resolves the original by its generated facade name and recorded **0 cases**, so the recording became stale.
- **Decision:** keep the original Kotlin top-level functions and their facade names unchanged in `:core:logic` (the `record`/`map` reference). `:core:native` compiles only the generated `rustanything/**` Java classes and excludes `com/xyzterm/logic/**`. Kotlin callers switch to `rustanything.ValidationNative.call_isValidSessionName(...)` / `call_isValidDeepLinkCommand(...)`. Generated shadow classes remain verification-only (`RUST_ANYTHING_MODE=old|shadow|new`).
- **Consequences:** no facade collision, `record` still finds the original, and `gate` passes after re-recording. The original Kotlin functions are no longer called by the app.

## Deferred (need their own ADR and operator approval)
Hand-port of `link2symlink.cpp`; porting leaf helpers from proot; replacing the Termux emulator with a Rust VT core. None are part of "done".
