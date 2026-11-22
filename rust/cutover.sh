#!/usr/bin/env bash
# Interactive cutover for the xyzterm Rust port.
# Run with: bash rust/cutover.sh
# Nothing here is irreversible: each step tells you how to roll back.
set -euo pipefail

cd "$(dirname "$0")/.."

pause() {
  printf '\n%s\n' "$1"
  read -r -p "Press Enter when done (or Ctrl-C to stop): " _
}

echo "=== xyzterm Rust cutover ==="
echo "Current mode is set in app/src/main/java/com/xyzterm/app/XyzTermApplication.kt"
echo "  old    -> original Kotlin only"
echo "  shadow -> original runs, Rust runs beside it, mismatches are logged (default)"
echo "  new    -> Rust answers the ported calls"

printf '\n--- Step 0: preflight (automated) ---\n'
if [ -d features/terminal/src/main/jniLibs ]; then
  echo "OK: jniLibs present"
  find features/terminal/src/main/jniLibs -maxdepth 2 -type f -name '*.so' -print
else
  echo "MISSING: features/terminal/src/main/jniLibs - CI builds these (rust-anything build --profile android)"
  exit 1
fi
echo "Rollback at any point: set rust-anything.mode to 'old' in XyzTermApplication.kt and rebuild."

pause "1. Confirm CI produced an APK containing the JNI libs (unzip -l app.apk | grep arm64-v8a)."

pause "2. Install that APK on a test device. Run in shadow mode and watch logcat:
   adb logcat | grep '\\[rust-anything\\] mismatch'
   There must be no mismatch lines. Rollback: set mode to 'old' and rebuild."

pause "3. Flip XyzTermApplication.kt to set rust-anything.mode to 'new', build a test APK,
   and exercise session create / backup restore / update check. Rollback: back to 'old'."

pause "4. Ship the 'new' build to your release channel (GitHub release / F-Droid) and watch for
   crash reports mentioning terminal_rs. Rollback: publish the previous APK."

echo
echo "Cutover complete. Keep the original Kotlin sources as reference until you are sure."
