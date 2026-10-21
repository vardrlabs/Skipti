#!/usr/bin/env bash
#
# Cross-compile skipti_core for iOS and assemble SkiptiCore.xcframework.
# D-44: driven entirely from the command line. If a step needs Xcode by
# hand it will rot and CI can never check it.
#
#   scripts/build-ios-core.sh            # build all three targets + framework
#
# Outputs (both under ios/Frameworks/):
#   libskipti_core-<target>.a            one per Rust target
#   libskipti_core-ios-sim.a             lipo'd fat simulator slice
#   SkiptiCore.xcframework/              device + fat-simulator slices,
#                                        headers/modulemap from the
#                                        committed bindings (make bindings)
#
# The xcframework is assembled MANUALLY (directory layout + Info.plist) so
# this script works with Command Line Tools alone, not just full Xcode.
# xcodebuild -create-xcframework produces the same structure; when full
# Xcode is present the manual form is still accepted by the linker.

set -euo pipefail
cd "$(dirname "$0")/.."

TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios)
OUT=ios/Frameworks
XCF="$OUT/SkiptiCore.xcframework"
BINDINGS=ios/Skipti/Bindings

command -v lipo >/dev/null || { echo "lipo not found (need macOS)"; exit 1; }

# Rust and C must agree on the minimum iOS version (D-45). The
# aarch64-apple-ios target defaults its object files to minos 10.0 while the
# SDK compiles SQLCipher's C against the current SDK (26.5), and
# ___chkstk_darwin (which the compiler emits for stack-probing on newer
# minima) does not exist in old runtimes: the result was an undefined-symbol
# link error plus a "newer than being linked" warning. 17.0 matches the app
# target's IPHONEOS_DEPLOYMENT_TARGET (D-44 chose it; see the pbxproj comment).
export IPHONEOS_DEPLOYMENT_TARGET=17.0

# The bindings must exist and be current: the freshness gate enforces the
# committed tree, but a clean checkout may not have run make bindings yet.
if [ ! -f "$BINDINGS/skipti_coreFFI.h" ]; then
  echo "bindings missing. Run: cargo build --release -p skipti-core && make bindings" >&2
  exit 1
fi

echo "==> Building static libs for: ${TARGETS[*]}"
for t in "${TARGETS[@]}"; do
  rustup target add "$t" >/dev/null 2>&1
  cargo build --release -p skipti-core --target "$t"
done

mkdir -p "$OUT"
for t in "${TARGETS[@]}"; do
  cp "target/$t/release/libskipti_core.a" "$OUT/libskipti_core-$t.a"
done

echo "==> Lipo fat simulator slice (arm64 + x86_64)"
lipo -create \
  "target/aarch64-apple-ios-sim/release/libskipti_core.a" \
  "target/x86_64-apple-ios/release/libskipti_core.a" \
  -output "$OUT/libskipti_core-ios-sim.a"

echo "==> Assembling $XCF"
rm -rf "$XCF"
mk_slice() { # dir, archive
  local dir="$1" archive="$2"
  mkdir -p "$XCF/$dir/SkiptiCore.framework/Headers" \
           "$XCF/$dir/SkiptiCore.framework/Modules"
  cp "$archive" "$XCF/$dir/SkiptiCore.framework/SkiptiCore"
  cp "$BINDINGS/skipti_coreFFI.h" "$XCF/$dir/SkiptiCore.framework/Headers/"
  # The module name matches what the generated Swift canImport()s.
  cat > "$XCF/$dir/SkiptiCore.framework/Modules/module.modulemap" <<'MAP'
module skipti_coreFFI {
    header "skipti_coreFFI.h"
    export *
}
MAP
}
mk_slice "ios-arm64" "$OUT/libskipti_core-aarch64-apple-ios.a"
mk_slice "ios-arm64_x86_64-simulator" "$OUT/libskipti_core-ios-sim.a"

cat > "$XCF/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>AvailableLibraries</key>
	<array>
		<dict>
			<key>LibraryIdentifier</key>
			<string>ios-arm64</string>
			<key>LibraryPath</key>
			<string>SkiptiCore.framework</string>
			<key>SupportedArchitectures</key>
			<array>
				<string>arm64</string>
			</array>
			<key>SupportedPlatform</key>
			<string>ios</string>
		</dict>
		<dict>
			<key>LibraryIdentifier</key>
			<string>ios-arm64_x86_64-simulator</string>
			<key>LibraryPath</key>
			<string>SkiptiCore.framework</string>
			<key>SupportedArchitectures</key>
			<array>
				<string>arm64</string>
				<string>x86_64</string>
			</array>
			<key>SupportedPlatform</key>
			<string>ios</string>
			<key>SupportedPlatformVariant</key>
			<string>simulator</string>
		</dict>
	</array>
	<key>CFBundlePackageType</key>
	<string>XFWK</string>
	<key>XCFrameworkFormatVersion</key>
	<string>1.0</string>
</dict>
</plist>
PLIST

echo "==> Verifying slices"
lipo -info "$XCF/ios-arm64/SkiptiCore.framework/SkiptiCore"
lipo -info "$XCF/ios-arm64_x86_64-simulator/SkiptiCore.framework/SkiptiCore"

# Symbol-presence check. Xcode's nm cannot read object files produced by
# Rust's newer LLVM ("Unknown attribute kind"), so prefer the llvm-nm that
# ships with the exact rustc that built these, and fall back to a raw
# symbol-name scan over the archive.
verify_symbols() {
  local archive="$1"
  local rust_nm
  rust_nm="$(dirname "$(rustup which rustc)")/lib/llvm-tools/bin/llvm-nm" || true
  if [ ! -x "$rust_nm" ]; then
    rust_nm="$(find "$(rustc --print sysroot)" -name llvm-nm -type f 2>/dev/null | head -1)"
  fi
  if [ -n "${rust_nm:-}" ] && [ -x "$rust_nm" ]; then
    "$rust_nm" -gU "$archive" 2>/dev/null | grep -c uniffi_skipti_core
  else
    strings "$archive" | grep -c uniffi_skipti_core_fn_func
  fi
}
device_symbols=$(verify_symbols "$XCF/ios-arm64/SkiptiCore.framework/SkiptiCore")
sim_symbols=$(verify_symbols "$XCF/ios-arm64_x86_64-simulator/SkiptiCore.framework/SkiptiCore")
[ "$device_symbols" -gt 0 ] && [ "$sim_symbols" -gt 0 ] || {
  echo "no uniffi symbols found in the framework: bindings/library mismatch"; exit 1;
}
echo "exported uniffi symbols: device: $device_symbols, simulator: $sim_symbols"

# §10.4 baseline (iOS < 30 MB): the Rust core is where the budget goes.
echo "==> Size baseline (§10.4)"
for t in "${TARGETS[@]}"; do
  printf '  %-28s %8s bytes\n' "libskipti_core-$t.a" \
    "$(stat -f%z "$OUT/libskipti_core-$t.a")"
done
du -sk "$XCF" | awk '{printf "  %-28s %8d KiB\n", "SkiptiCore.xcframework", $1}'
du -sk native/fonts | awk '{printf "  %-28s %8d KiB\n", "native/fonts (TTF)", $1}'

echo "Done: $XCF"
