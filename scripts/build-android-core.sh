#!/usr/bin/env bash
#
# Cross-compile skipti_core for Android and place the .so where Gradle finds
# it. D-44's rule applied to the second shell: driven entirely from the
# command line: a step that needs Studio by hand will rot and CI can never
# check it.
#
#   scripts/build-android-core.sh          # both ABIs -> jniLibs
#
# Outputs (committed-ignored, produced by this script):
#   android/app/src/main/jniLibs/<abi>/libskipti_core.so
#
# ABI set: arm64-v8a (real devices) + x86_64 (the emulator on Apple Silicon).
# armeabi-v7a is deliberately absent: it doubles the C compile of bundled
# SQLCipher for hardware the SPEC's minSdk 26 floor leaves vanishingly rare,
# and an unused slice is untested slice. Add it WITH evidence, not by habit.
# The Gradle project consumes these in place (sourceSets default), exactly as
# iOS consumes its committed Bindings in place: a second copy would drift past
# the freshness gate.
#
# Toolchain pinning: JAVA_HOME must point at Studio's JBR before any gradle
# run (see Makefile); this script itself only needs rustup + the NDK clangs.

set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# Portable host tag (CI's Linux runners are first-class now): darwin|linux-x86_64.
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *)      HOST_TAG="linux-x86_64" ;;
esac
# Explicit override wins; otherwise the NEWEST NDK present. Pinning lives in
# package.xml per NDK, not in this filename.
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME/toolchains" ]; then
  NDK="$ANDROID_NDK_HOME"
else
  NDK="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$NDK" ] || { echo "no NDK found under $SDK/ndk: install via Studio SDK Manager or set ANDROID_NDK_HOME" >&2; exit 1; }
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
OUT=android/app/src/main/jniLibs

[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin" ] || {
  echo "NDK at $NDK has no toolchain for host tag $HOST_TAG" >&2
  exit 1
}

# API level matches minSdk in app/build.gradle.kts (SPEC: Kotlin, Jetpack
# Compose, minSdk 26, targetSdk current). Rust objects bake the same floor.
API=26

echo "==> Building cdylib for: aarch64-linux-android x86_64-linux-android"
for t in aarch64-linux-android x86_64-linux-android; do
  rustup target add "$t" >/dev/null 2>&1
  cli="$TOOLCHAIN/bin/${t}${API}-clang"
  [ -x "$cli" ] || { echo "missing NDK wrapper $cli" >&2; exit 1; }
  usn="$(echo "$t" | tr '[:lower:]-' '[:upper:]_')"
  und="$(echo "$t" | tr '-' '_')"
  export "CARGO_TARGET_${usn}_LINKER=$cli"
  # bundled SQLCipher's C must be compiled BY the same NDK toolchain the
  # link uses: cc picks these up; defaulting to host clang would produce a
  # fat .so with the wrong libgcc/unwind expectations or just fail at link.
  # Both name spellings are exported: which one cc consults is its business.
  export "CC_${und}=$cli"
  export "CC_${usn}=$cli"
  export "AR_${und}=$TOOLCHAIN/bin/llvm-ar"
  export "AR_${usn}=$TOOLCHAIN/bin/llvm-ar"
  export "RANLIB_${und}=$TOOLCHAIN/bin/llvm-ranlib"
  export "RANLIB_${usn}=$TOOLCHAIN/bin/llvm-ranlib"
  cargo build --release -p skipti-core --target "$t"
done

echo "==> Placing .so into $OUT"
mkdir -p "$OUT/arm64-v8a" "$OUT/x86_64"
cp target/aarch64-linux-android/release/libskipti_core.so "$OUT/arm64-v8a/libskipti_core.so"
cp target/x86_64-linux-android/release/libskipti_core.so      "$OUT/x86_64/libskipti_core.so"

echo "==> Verifying exported symbols"
verify_symbols() { # .so path
  local so="$1"
  local nm="$TOOLCHAIN/bin/llvm-nm"
  if [ -x "$nm" ]; then
    "$nm" -D --defined-only "$so" 2>/dev/null | grep -c uniffi_skipti_core || true
  else
    strings "$so" | grep -c uniffi_skipti_core_fn_func || true
  fi
}
arm_symbols=$(verify_symbols "$OUT/arm64-v8a/libskipti_core.so")
x86_symbols=$(verify_symbols "$OUT/x86_64/libskipti_core.so")
[ "$arm_symbols" -gt 0 ] && [ "$x86_symbols" -gt 0 ] || {
  echo "no uniffi symbols found in the .so files: bindings/library mismatch"; exit 1;
}
echo "exported uniffi symbols: arm64-v8a: $arm_symbols, x86_64: $x86_symbols"

# §10.4 baseline (iOS < 30 MB is the budget the shells share): the Rust core
# is where the budget goes, so measure it per ABI.
echo "==> Size baseline (§10.4)"
du -sk native/fonts | awk '{printf "  %-28s %8d KiB\n", "fonts (TTF)", $1}'
ls -l "$OUT"/arm64-v8a/libskipti_core.so | awk '{printf "  %-28s %8d bytes\n", "libskipti_core.so arm64-v8a", $5}'
ls -l "$OUT"/x86_64/libskipti_core.so | awk '{printf "  %-28s %8d bytes\n", "libskipti_core.so x86_64", $5}'

echo "Done: jniLibs under $OUT"
