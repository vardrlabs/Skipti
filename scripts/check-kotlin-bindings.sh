#!/usr/bin/env bash
#
# COMPILE the generated Kotlin bindings standalone, deliberately NOT via the
# app target. D-91's finding generalised (§5.17): the freshness gate proves
# BYTES, not buildability; byte-identical-but-broken Kotlin passed every gate
# until Android forced a first-ever compile through the whole app pipeline,
# which needs an SDK/NDK this machine happens to have but no other must.
# This check needs ONLY a JDK + a Kotlin compiler, so it runs everywhere the
# freshness gate runs, exactly the layer that was blind before.
#
# The compiled unit is the committed binding tree itself
# (android/app/src/main/kotlin/uniffi/skipti_core/*.kt) against JNA, the one
# runtime the generated code imports at every boundary call. Swift has the
# same exposure in principle and is covered by the ios-app leg compiling
# SkiptiCore.swift for real; the asymmetry is recorded here because that leg
# is heavier and already load-bearing.
#
# Dependency pinning per standing rule 4: jna-5.19.1.jar fetched ONCE from
# Maven Central into android/.kotlinc-cache/, sha256-verified BEFORE use
# (mismatch = fail loudly, never trust an unverified jar), cached thereafter.

set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "JBR/java not found at $JAVA_HOME" >&2; exit 1; }
export JAVA_HOME

KOTLINC_BIN="${STUDIO_KOTLINC:-/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc}"
if [ ! -x "$KOTLINC_BIN/bin/kotlinc" ]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC_BIN="$(dirname "$(dirname "$(command -v kotlinc)")")"
  else
    echo "no kotlinc (Studio bundles one at Android Studio.app/Contents/plugins/Kotlin/kotlinc)" >&2
    exit 1
  fi
fi

BINDINGS_DIR=android/app/src/main/kotlin/uniffi/skipti_core
shopt -s nullglob
KT_FILES=("$BINDINGS_DIR"/*.kt)
[ ${#KT_FILES[@]} -gt 0 ] || { echo "no Kotlin bindings found under $BINDINGS_DIR" >&2; exit 1; }

CACHE=android/.kotlinc-cache
mkdir -p "$CACHE"
JAR="$CACHE/jna-5.19.1.jar"
PINNED_SHA="4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae"
if [ ! -f "$JAR" ]; then
  echo "==> fetching jna-5.19.1.jar (pinned by sha256)"
  curl -sSL --fail -o "$JAR.part" \
    https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar
  got="$(shasum -a 256 "$JAR.part" | awk '{print $1}')"
  [ "$got" = "$PINNED_SHA" ] || { echo "sha256 mismatch ($got), refusing to use" >&2; rm -f "$JAR.part"; exit 1; }
  mv "$JAR.part" "$JAR"
fi

OUT="$CACHE/classes-out"
rm -rf "$OUT"; mkdir -p "$OUT"

"$KOTLINC_BIN/bin/kotlinc" -jvm-target 17 -classpath "$JAR" -d "$OUT" "${KT_FILES[@]}"

n=$(find "$OUT" -name '*.class' | wc -l | tr -d ' ')
[ "$n" -gt 0 ] || { echo "kotlinc produced no classes, refusing to call this green" >&2; exit 1; }
echo "KOTLIN BINDINGS COMPILE OK ($n classes from ${#KT_FILES[@]} file(s))"
