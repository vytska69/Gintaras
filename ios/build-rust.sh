#!/usr/bin/env bash
# Build the shared Rust core (gintaras-core) for iOS device, iOS simulator and
# Mac Catalyst, and package it as GintarasCoreFFI.xcframework for the Xcode
# app/extension to link. Run on macOS with Xcode + Rust installed.
#
# Independent toggles (1/0); default builds everything:
#   DEVICE=1     aarch64-apple-ios            (device, stable)
#   SIM=1        ios-sim universal            (simulator, stable)
#   CATALYST=1   *-apple-ios-macabi universal (Mac Catalyst — tier-3, needs the
#                nightly toolchain's build-std)
# Convenience modes:
#   IPHONEOS_ONLY=1    -> DEVICE only (fast path for an .ipa)
#   MACCATALYST_ONLY=1 -> CATALYST only (fast path for a Mac .pkg)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../core"
LIB=libgintaras_core.a

DEVICE="${DEVICE:-1}"
SIM="${SIM:-1}"
CATALYST="${CATALYST:-1}"
if [ "${IPHONEOS_ONLY:-0}" = "1" ]; then DEVICE=1; SIM=0; CATALYST=0; fi
if [ "${MACCATALYST_ONLY:-0}" = "1" ]; then DEVICE=0; SIM=0; CATALYST=1; fi

( cd "$CORE"
  if [ "$DEVICE" = "1" ]; then
    rustup target add aarch64-apple-ios >/dev/null 2>&1 || true
    cargo build --release --target aarch64-apple-ios
  fi
  if [ "$SIM" = "1" ]; then
    rustup target add aarch64-apple-ios-sim x86_64-apple-ios >/dev/null 2>&1 || true
    cargo build --release --target aarch64-apple-ios-sim
    cargo build --release --target x86_64-apple-ios
    mkdir -p target/ios-sim-universal
    lipo -create \
      target/aarch64-apple-ios-sim/release/$LIB \
      target/x86_64-apple-ios/release/$LIB \
      -output target/ios-sim-universal/$LIB
  fi
  if [ "$CATALYST" = "1" ]; then
    rustup toolchain install nightly >/dev/null 2>&1 || true
    rustup component add rust-src --toolchain nightly >/dev/null 2>&1 || true
    cargo +nightly build -Z build-std=std,panic_abort --release --target aarch64-apple-ios-macabi
    cargo +nightly build -Z build-std=std,panic_abort --release --target x86_64-apple-ios-macabi
    mkdir -p target/maccatalyst-universal
    lipo -create \
      target/aarch64-apple-ios-macabi/release/$LIB \
      target/x86_64-apple-ios-macabi/release/$LIB \
      -output target/maccatalyst-universal/$LIB
  fi
)

HDR="$(mktemp -d)"
cp "$CORE/include/gintaras.h" "$HDR/"
cat > "$HDR/module.modulemap" <<EOM
module GintarasCoreFFI {
    header "gintaras.h"
    export *
}
EOM

OUT="$HERE/GintarasCoreFFI.xcframework"
rm -rf "$OUT"
ARGS=()
[ "$DEVICE" = "1" ]   && ARGS+=( -library "$CORE/target/aarch64-apple-ios/release/$LIB" -headers "$HDR" )
[ "$SIM" = "1" ]      && ARGS+=( -library "$CORE/target/ios-sim-universal/$LIB" -headers "$HDR" )
[ "$CATALYST" = "1" ] && ARGS+=( -library "$CORE/target/maccatalyst-universal/$LIB" -headers "$HDR" )
xcodebuild -create-xcframework "${ARGS[@]}" -output "$OUT"
echo "Built $OUT"
