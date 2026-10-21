.PHONY: gate gate-full hooks test clippy fmt audit deny sbom wasm bindings clean ios-core ios-app ios-test android-core android-app android-test cargo-build-release-core

# The gate is the single source of truth for "is this tree green?".
# Every other target below is one slice of it, kept for running in isolation.
# `gate` is what the pre-commit hook runs; `gate-full` adds the release sweeps
# and supply-chain audits and is what you run before claiming a phase done.
gate:
	./scripts/gate.sh

gate-full:
	./scripts/gate.sh --full

hooks:
	git config core.hooksPath .githooks
	@echo "Hooks installed: .githooks/pre-commit will refuse a red commit."
	@echo "Bypass for a deliberate known-red commit: git commit --no-verify"

test:
	cargo test --workspace
	cargo test --workspace --release -- --ignored

clippy:
	cargo clippy --workspace --all-targets -- -D warnings

fmt:
	cargo fmt --all -- --check

audit:
	cargo audit

deny:
	cargo deny check

sbom:
	cargo cyclonedx --all --format json

wasm:
	cargo build -p skipti-core --target wasm32-unknown-unknown

# Regenerates from a FRESH release dylib. Learned the hard way (D-46):
# running uniffi-bindgen against a stale dylib silently emits stale
# bindings that the freshness gate cannot distinguish from current ones.
# Host lib suffix differs (macOS .dylib vs Linux .so). CI exposed this by
# being the first non-macOS machine ever to run make bindings (D-99).
HOST_LIB_EXT := $(shell test "$$(uname -s)" = "Darwin" && echo dylib || echo so)
bindings: cargo-build-release-core
	cargo run -p skipti-core --bin uniffi-bindgen -- generate --library target/release/libskipti_core.$(HOST_LIB_EXT) --language kotlin --out-dir android/app/src/main/kotlin
	cargo run -p skipti-core --bin uniffi-bindgen -- generate --library target/release/libskipti_core.$(HOST_LIB_EXT) --language swift --out-dir ios/Skipti/Bindings

# Android pipeline (D-91). The JBR is Studio's OWN bundled JDK, the one its
# AGP is tested against; a second JDK from brew risks command-line builds
# diverging from Studio builds, which is exactly the rot D-44 warns about.
JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
export JAVA_HOME
# Same default the cross-compile script resolves; exported so Gradle finds the
# SDK without a machine-specific local.properties (that file stays uncommitted).
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export ANDROID_HOME

# COMPILES the committed Kotlin bindings standalone (bytes != buildable, §5.17).
# Needs only a JDK + kotlinc: no Android SDK, so it runs wherever the
# freshness gate does; scripts/check-kotlin-bindings.sh holds the reasoning.
bindings-kotlin:
	./scripts/check-kotlin-bindings.sh

android-core:
	./scripts/build-android-core.sh

# JVM unit tests for the Android shell (D-108). These are NOT Robolectric or
# instrumentation: they call the REAL core through the app's own JNA bindings,
# loading the HOST cdylib from target/debug via jna.library.path (set in
# android/app/build.gradle.kts). So `cargo build -p skipti-core` is a genuine
# prerequisite, not a convenience: without it the tests fail to load the
# library rather than silently passing on a mock.
#
# Separate target from android-app on purpose: assembleDebug proves the APK
# PACKAGES, this proves the Kotlin LOGIC runs against real core. D-91's lesson
# ("committed Kotlin that had never compiled") one level up: these goldens had
# no gate leg when they landed, and a suite nothing runs is a suite that rots.
android-test:
	cargo build -p skipti-core
	cd android && ./gradlew :app:testPlayDebugUnitTest --console=plain --no-daemon
	@echo "--> JVM goldens (real core through the app's JNA bindings):"
	@python3 -c "import glob,xml.etree.ElementTree as ET; \
fs=sorted(glob.glob('android/app/build/test-results/testPlayDebugUnitTest/*.xml')); \
rows=[(f.split('/')[-2].replace('test','',1).replace('UnitTest','').replace('Debug','').lower(), ET.parse(f).getroot()) for f in fs]; \
t=sum(int(r.get('tests')) for _,r in rows); f=sum(int(r.get('failures'))+int(r.get('errors')) for _,r in rows); \
[print('    [%-11s] %-22s tests=%s failures=%s' % (v, r.get('name').rsplit('.',1)[-1], r.get('tests'), int(r.get('failures'))+int(r.get('errors')))) for v,r in rows]; \
vs=len({v for v,_ in rows}); \
print('    TOTAL %d executions = %d tests x %d variant(s), %d failed' % (t, t//max(vs,1), vs, f)); \
exit(1 if f else 0)"

# D-116: ADR-0010's two flavours assemble here: both debug variants, and
# the play manifest contract is asserted against the MERGED manifest (the
# place a permission would arrive from a library; the source manifest
# proves nothing).
android-app: android-core
	cd android && ./gradlew :app:assemblePlayDebug --console=plain --no-daemon
	@./scripts/check-play-manifest.sh
	@python3 -I scripts/test_check_play_manifest.py
	@echo "--> packaged-native check (both ABIs inside the play APK):"
	@for apk in android/app/build/outputs/apk/play/debug/app-play-debug.apk; do \
		unzip -l $$apk | grep -c "lib/arm64-v8a/libskipti_core.so" >/dev/null || { echo "    MISSING arm64-v8a/libskipti_core.so in $$apk"; exit 1; }; \
		unzip -l $$apk | grep -q "lib/x86_64/libskipti_core.so" || { echo "    MISSING x86_64/libskipti_core.so in $$apk"; exit 1; }; \
		du -sk $$apk | awk '{printf "    %s %6d KiB\n", FILENAME, $$1}' FILENAME=$$apk; \
	done

# iOS pipeline (D-44). Cross-compiles the three Rust targets and assembles
# SkiptiCore.xcframework, which works with Command Line Tools alone. Needs the
# iOS SDK for the bundled SQLCipher C: full Xcode required for the compile
# step itself; see BUILD-LOG §4.4 for the honest state.
ios-core:
	./scripts/build-ios-core.sh

# Boot-once-and-reuse the named simulator (D-96): the per-invocation device
# lifecycle xcodebuild applies when resolving name= is what intermittently
# kills the runner launch; the gate owns the boot instead and waits for
# CoreSimulator to declare it finished before any test runs.
.PHONY: ios-sim-udid
ios-sim-udid:
	@./scripts/ensure-ios-sim.sh "$(SIM)"

# Build the app + run the vault smoke tests on a simulator. REQUIRES full
# Xcode with an iOS simulator runtime; everything else in ios/ is written
# to be verified by exactly this target once Xcode lands.
SIM ?= iPhone 17

ios-app: ios-core
	@mkdir -p ios/build/logs
	@mkdir -p ios/build/logs && $(MAKE) --no-print-directory ios-sim-udid > ios/build/logs/simulator.udid
	xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti \
	  -derivedDataPath ios/build \
	  -destination "platform=iOS Simulator,id=$$(cat ios/build/logs/simulator.udid)" \
	  -parallel-testing-enabled NO -collect-test-diagnostics never test \
	  > ios/build/logs/ios-test-full.log 2>&1; rc=$$?; tail -40 ios/build/logs/ios-test-full.log; [ $$rc -eq 0 ] || { echo "--> FULL LOG PRESERVED: ios/build/logs/ios-test-full.log"; exit $$rc; }; echo "** TEST SUCCEEDED **"
	xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti \
	  -derivedDataPath ios/build \
	  -configuration Release -destination 'generic/platform=iOS' \
	  CODE_SIGNING_ALLOWED=NO build \
	  > ios/build/logs/ios-release-build.log 2>&1; rc=$$?; [ $$rc -eq 0 ] || { echo "--> RELEASE BUILD FAILED (full log: ios/build/logs/ios-release-build.log)"; tail -60 ios/build/logs/ios-release-build.log; exit $$rc; }
	xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti \
	  -derivedDataPath ios/build \
	  -configuration Release -destination 'generic/platform=iOS' \
	  CODE_SIGNING_ALLOWED=NO build \
	  > ios/build/logs/ios-release-build.log 2>&1; rc=$$?; [ $$rc -eq 0 ] || { echo "--> RELEASE BUILD FAILED (full log: ios/build/logs/ios-release-build.log)"; tail -60 ios/build/logs/ios-release-build.log; exit $$rc; }
	@echo "--> §10.4 iOS size baseline (< 30 MB):"
	@du -sk ios/build/Build/Products/Release-iphoneos/Skipti.app | awk '{printf "    Skipti.app (device, unsigned)   %6d KiB\n", $$1}'
	@ls -l ios/build/Build/Products/Release-iphoneos/Skipti.app/Skipti | awk '{printf "    linked binary                  %6d bytes\n", $$5}'
	@ls -l ios/Frameworks/libskipti_core-aarch64-apple-ios.a | awk '{printf "    Rust staticlib (unstripped .a) %6d bytes\n", $$5}'
	@du -sk native/fonts | awk '{printf "    fonts (TTF, bundled)           %6d KiB\n", $$1}' 

clean:
	cargo clean

cargo-build-release-core:
	cargo build --release -p skipti-core
