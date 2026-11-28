# rust/MAP.md

## Scope
- **In scope:** every non-UI, non-framework logic unit that can be reshaped into a bridgeable Kotlin/JVM seam.
- **Stays Kotlin:** `@Composable`, Activity, Service, `Context`-bound I/O, SAF, Keystore, `Settings`, theme/icons, crash/log screens, `core/components`, `baselineprofile`, `macrobenchmark`, `buildSrc`. See ADR-001.
- **Stays other language:** proot C (ADR-002), `link2symlink.cpp` (ADR-003), Termux emulator/view (ADR-004), guest shell `features/terminal/src/main/assets/terminal/*.sh`.
- **Stays Kotlin inside otherwise ported files:** `TerminalBackup.create`/`restore` orchestration, filesystem helpers (`restoreRollback`, `isRealDirectory`).

## Analyze snapshot — 2026-11-28, rust-anything 5.0.2
Reports: `/data/user/0/com.xyzterm.app/tmp/opencode/ra-phase0/{kotlin,java,c}.json` (outside the repo). Regenerate at G2 with `rust-anything analyze . --lang <lang> --json`.

| Lang | units | ready | blocked/caution | keep | start_here | parse errors |
|---|---|---|---|---|---|---|
| Kotlin | 44 | 3 | 41 blocked | 0 | 0 | 1 |
| Java | 3 | 1 | 2 caution | 0 | 0 | 0 (0 bridgeable exports in all 3) |
| C | 23 | 4 | 16 caution | 3 | 4 (readiness 0) | 39 |

After G2, Kotlin is 45 units / 4 ready / 41 blocked / 1 parse error, with `start_here = ['core/logic/src/main/kotlin/com/xyzterm/logic/']` — readiness 100, `bridgeable = 2`, `exports = 2`. The three original `ready` units have `bridgeable = 0`: `app/src/main/java/com/xyzterm/app/`, `buildSrc/src/main/kotlin/DownloadRootfsTask.kt`, `core/main/src/main/java/com/xyzterm/events/`. Pointing rust-anything at the untouched repo ports nothing — seam first (Phase 2).

## Units — state
`pilot` = first pipeline proof; `candidate` = seam not extracted yet; `stays` = out of scope.

| Candidate | Anchor | Blocker → seam | State |
|---|---|---|---|
| `SessionNames` | `FT/terminal/SessionNames.kt:3` → `core/logic/src/main/kotlin/com/xyzterm/logic/SessionNames.kt:4` | visibility only | seam ready (delegate in place) |
| `DeepLinkCommand` | `FT/terminal/DeepLinkCommand.kt:3` → `core/logic/src/main/kotlin/com/xyzterm/logic/DeepLinkCommand.kt:5` | visibility; UTF-16 length + Kotlin whitespace traps | seam ready (delegate in place) |
| `RootfsInstaller.parseRootfsManifest` | `FT/terminal/RootfsInstaller.kt:27,50,56` | data-class returns and `File(linkPath).parent` → path `String` | candidate |
| `TerminalBackup.detectBackupLayout`, `isUnsafeBackupLink` | `FT/terminal/TerminalBackup.kt:38,52` | enum return → `String` | candidate |
| `UpdateManager.updateApkName`, `isNewer` | `CM/UpdateManager.kt:32,59` | `Array<String>` → `List<String>`; move `object` member out | candidate |
| `BackupCrypto` | `FT/terminal/BackupCrypto.kt:17-25,36-72` | `File` → paths, inject salt/IV, return output SHA-256 | candidate |
| `UserBindings` | `FT/exec/UserBindings.kt:13-59` | `Binding` data class + filesystem host-path check | candidate |
| `BackupSettings` | `FT/terminal/BackupSettings.kt:27-93` | `Map<String, Any?>` + gson + `put` callback → JSON string, returned data | candidate |
| ubuntuProcess bind list | `FT/exec/ubuntuProcess.kt:43-92` | reads `Settings`, `Context`, `File.exists()` → parameters | candidate |
| `SandboxEnv` | `FT/exec/SandboxEnv.kt` | env map reads `Settings`, `Context` → parameters | candidate |
| `SettingsSearch` | `CM/settings/SettingsSearch.kt` | reshape to allowed types | candidate |
| `KeybindingsManager` + `KeyUtils` | `CM/commands/KeybindingsManager.kt`, `CM/settings/keybinds/KeyUtils.kt` | reshape to allowed types | candidate |
| `ColorUtils` | `CM/color/ColorUtils.kt` | reshape to allowed types | candidate |
| `PathUtils` | `CM/utils/PathUtils.kt` | reshape to allowed types | candidate |
| `VirtualKeysInfo` | `FT/terminal/virtualkeys/VirtualKeysInfo.java:202` | Java, 0 bridgeable exports → needs a data-only function (API change) | candidate |

`FT/` = `features/terminal/src/main/java/com/xyzterm/`; `CM/` = `core/main/src/main/java/com/xyzterm/`.

## Environment facts
- Branch `rust-port` at `ec937f27c`; origin exposes only `main`; no `dev` (ADR-007).
- Installed: rust-anything 5.0.2, Kotlin 2.4.21, JDK 25.0.5.1, NDK 28.0.13004108, `aarch64-linux-android` + `armv7-linux-androideabi` + `x86_64-linux-android` targets, adb 1.0.41 with no device attached.
- Gaps to close at G4/G5: NDK 30.0.16248370, JDK 21 parity with CI, device operator.

## Open questions
None from G1 — Q1–Q12 all accepted. Deferred decisions are listed in `DECISIONS.md`; none blocks the pilot.

## G3 pilot — `rust/validation`
- `bridge` from `core/logic` as root (repo-root auto-detect picks Java and cannot see Kotlin units); `--end-state bindings`; crate `rust/validation`.
- `record`: 477 cases (238 session names, 239 deep-link commands) from the JUnit fixtures plus UTF-16/whitespace traps. `diff`: **477/477**. `bench`: **8.3x slower overall** (6.4x/4.6x per call) — migration, not speed.
- `gate`: pass (fmt, clippy, cargo test 3 tests, 38 Rust lines for 12 Kotlin, no `unsafe` without SAFETY, no `unimplemented!`, API and behavior).
- Android JNI libs built for arm64-v8a and armeabi-v7a, API 26, 3 entry points checked. Local host is aarch64 while NDK tools are x86_64: an `llvm-nm` shim was needed outside the repo, and NDK 28 was used because the NDK 30 download did not finish.
- `diff --profile android` handed to the operator: `rust-anything diff /home/xyzterm/rust/validation --profile android --device <serial>`.
- `shadow` generated `rust/validation/java/com/xyzterm/logic/DeepLinkCommandKtShadow.java`; cut-over is ADR-013, deferred to G4.

## Next gate
G4: Gradle/CI integration — `:core:native` owns `jniLibs/` and generated Java, `preBuild` cargo task, keep rules, workflows + Dockerfile, `reproducible.yml`, APK size and cold-start deltas. Then switch Kotlin callers per ADR-013.
