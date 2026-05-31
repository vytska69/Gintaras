#!/usr/bin/env bash
#
# Build a MODERN LuaJIT 2.1 for the app and swap it in for the 2015 alpha runtime.
#
# Why: the device's prebuilt libluajit.so is LuaJIT 2.1.0-alpha (2015). On modern
# Android (10+) the JIT cannot run (W^X / SELinux forbids writable+executable
# memory), so the engine falls back to that old INTERPRETER, which mis-evaluates
# voicesynth's FFI DSP and emits all-zero (silent) PCM — with no Lua error.
# A current LuaJIT 2.1 interpreter evaluates it correctly (verified off-device).
#
# librosasofttts.so links libluajit by the PUBLIC Lua 5.1 / LuaJIT C API only and
# needs DT_NEEDED soname "libluajit.so", so a modern build is a drop-in once we
# force TARGET_SONAME=libluajit.so.
#
# We build BOTH:
#   * a native 32-bit HOST luajit  -> recompiles main.bin (bytecode must match the
#     32-bit, non-GC64 target, hence -m32)
#   * the armeabi-v7a libluajit.so -> ships in jniLibs
# from the SAME source, so the shipped bytecode and runtime are version-matched.
#
# Usage: tools/ci_build_luajit.sh <NDK_ROOT>
set -euo pipefail

NDK="${1:?usage: ci_build_luajit.sh <NDK_ROOT>}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
NDKABI=21
TRIPLE=armv7a-linux-androideabi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

echo "==> Cloning LuaJIT v2.1"
git clone --depth 1 --branch v2.1 https://github.com/LuaJIT/LuaJIT.git "$WORK/luajit"
cd "$WORK/luajit"
REV="$(git rev-parse --short HEAD)"
echo "    LuaJIT @ $REV"

# --- 1) native 32-bit host luajit (to recompile main.bin) --------------------
echo "==> Building native 32-bit host luajit"
make clean >/dev/null 2>&1 || true
make -j"$(nproc)" CC="gcc -m32" >/dev/null
cp src/luajit "$WORK/hostluajit"
cp -r src/jit "$WORK/jit"
"$WORK/hostluajit" -v

# --- 2) cross-compiled armeabi-v7a libluajit.so ------------------------------
echo "==> Cross-building armeabi-v7a libluajit.so (soname libluajit.so)"
make clean >/dev/null 2>&1 || true
make -j"$(nproc)" \
  HOST_CC="gcc -m32" \
  CROSS="$TOOLCHAIN/bin/llvm-" \
  STATIC_CC="$TOOLCHAIN/bin/${TRIPLE}${NDKABI}-clang" \
  DYNAMIC_CC="$TOOLCHAIN/bin/${TRIPLE}${NDKABI}-clang -fPIC" \
  TARGET_LD="$TOOLCHAIN/bin/${TRIPLE}${NDKABI}-clang" \
  TARGET_AR="$TOOLCHAIN/bin/llvm-ar rcus" \
  TARGET_STRIP="$TOOLCHAIN/bin/llvm-strip" \
  TARGET_SYS=Linux \
  TARGET_SONAME=libluajit.so \
  TARGET_FLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16" \
  BUILDMODE=dynamic >/dev/null
test -f src/libluajit.so
"$TOOLCHAIN/bin/llvm-strip" src/libluajit.so

DEST="$REPO/app/src/main/jniLibs/armeabi-v7a/libluajit.so"
cp src/libluajit.so "$DEST"
echo "    soname: $(readelf -d "$DEST" | grep SONAME || true)"
echo "    installed -> $DEST ($(stat -c%s "$DEST") bytes)"

# --- 3) recompile main.bin with the matching host luajit ---------------------
echo "==> Rebuilding main.bin (INSTRUMENT=1) with matching LuaJIT $REV"
# rebuild_mainbin.py resolves jit/*.lua from the luajit binary's dir.
cp -r "$WORK/jit" "$WORK/jit_beside_host" 2>/dev/null || true
mkdir -p "$(dirname "$WORK/hostluajit")/jit"
cp "$WORK"/jit/*.lua "$(dirname "$WORK/hostluajit")/jit/" 2>/dev/null || true
INSTRUMENT=1 python3 "$REPO/tools/rebuild_mainbin.py" \
  "$WORK/hostluajit" \
  "$REPO/engine/extracted/assets/main.bin" \
  "$REPO/app/src/main/assets/main.bin" \
  "$WORK/rebuild"
echo "    main.bin -> $(stat -c%s "$REPO/app/src/main/assets/main.bin") bytes"

echo "==> Done (LuaJIT $REV)"
