#!/usr/bin/env bash
# Build the shared Rust core (gintaras-core) for iOS device, iOS simulator and
# Mac Catalyst, and package it as GintarasCoreFFI.xcframework for the Xcode
# app/extension to link. Run on macOS with Xcode + Rust installed.
#
# Mac Catalyst targets (*-apple-ios-macabi) are Rust tier-3, so they have no
# prebuilt std — they are built with the nightly toolchain's build-std. Set
# CATALYST=0 to skip them (iOS/simulator only).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../core"
CATALYST="${CATALYST:-1}"
LIB=libgintaras_core.a

rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios >/dev/null 2>&1 || true

( cd "$CORE"
  cargo build --release --target aarch64-apple-ios
  cargo build --release --target aarch64-apple-ios-sim
  cargo build --release --target x86_64-apple-ios
  mkdir -p target/ios-sim-universal
  lipo -create \
    target/aarch64-apple-ios-sim/release/$LIB \
    target/x86_64-apple-ios/release/$LIB \
    -output target/ios-sim-universal/$LIB

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
ARGS=(
  -library "$CORE/target/aarch64-apple-ios/release/$LIB" -headers "$HDR"
  -library "$CORE/target/ios-sim-universal/$LIB" -headers "$HDR"
)
if [ "$CATALYST" = "1" ]; then
  ARGS+=( -library "$CORE/target/maccatalyst-universal/$LIB" -headers "$HDR" )
fi
xcodebuild -create-xcframework "${ARGS[@]}" -output "$OUT"
echo "Built $OUT"
