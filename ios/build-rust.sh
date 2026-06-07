#!/usr/bin/env bash
# Build the shared Rust core (gintaras-core) for iOS device + simulator and
# package it as GintarasCoreFFI.xcframework for the Xcode app/extension to link.
# Run on macOS with Xcode + Rust installed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../core"

rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios >/dev/null 2>&1 || true

( cd "$CORE"
  cargo build --release --target aarch64-apple-ios
  cargo build --release --target aarch64-apple-ios-sim
  cargo build --release --target x86_64-apple-ios
  mkdir -p target/ios-sim-universal
  lipo -create \
    target/aarch64-apple-ios-sim/release/libgintaras_core.a \
    target/x86_64-apple-ios/release/libgintaras_core.a \
    -output target/ios-sim-universal/libgintaras_core.a
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
xcodebuild -create-xcframework \
  -library "$CORE/target/aarch64-apple-ios/release/libgintaras_core.a" -headers "$HDR" \
  -library "$CORE/target/ios-sim-universal/libgintaras_core.a" -headers "$HDR" \
  -output "$OUT"
echo "Built $OUT"
