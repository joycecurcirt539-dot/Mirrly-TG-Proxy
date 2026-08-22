#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "============================================="
echo "Mirrly TG Proxy - Building Native Rust Engine"
echo "============================================="

# 1. Locate Android NDK
NDK_DIR=""
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK_DIR="$ANDROID_NDK_ROOT"
elif [ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ]; then
    NDK_DIR="$NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_HOME." >&2
    exit 1
fi

# Detect host OS for prebuilt toolchain directory
HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_ARCH="$(uname -m)"
TOOLCHAIN_HOST=""
if [ "$HOST_OS" = "linux" ]; then
    TOOLCHAIN_HOST="linux-x86_64"
elif [ "$HOST_OS" = "darwin" ]; then
    TOOLCHAIN_HOST="darwin-x86_64"
fi

NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$TOOLCHAIN_HOST/bin"
if [ ! -d "$NDK_BIN" ]; then
    # Fallback to wildcard search if darwin-aarch64 or similar
    NDK_BIN="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)/bin"
fi

echo "Using Android NDK: $NDK_DIR"
echo "Toolchain bin:    $NDK_BIN"

export PATH="$NDK_BIN:$PATH"

# 2. Configure API level (minSdk = 26)
API_LEVEL=26

export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android${API_LEVEL}-clang"
export CC_armv7_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi${API_LEVEL}-clang"
export CC_i686_linux_android="$NDK_BIN/i686-linux-android${API_LEVEL}-clang"
export CC_x86_64_linux_android="$NDK_BIN/x86_64-linux-android${API_LEVEL}-clang"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android${API_LEVEL}-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi${API_LEVEL}-clang"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$NDK_BIN/i686-linux-android${API_LEVEL}-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android${API_LEVEL}-clang"

export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
export AR_armv7_linux_androideabi="$NDK_BIN/llvm-ar"
export AR_i686_linux_android="$NDK_BIN/llvm-ar"
export AR_x86_64_linux_android="$NDK_BIN/llvm-ar"

TARGETS=(
    "aarch64-linux-android:arm64-v8a"
    "armv7-linux-androideabi:armeabi-v7a"
    "i686-linux-android:x86"
    "x86_64-linux-android:x86_64"
)

# 3. Ensure Rust targets are installed
for item in "${TARGETS[@]}"; do
    TARGET="${item%%:*}"
    rustup target add "$TARGET" || true
done

# 4. Build each target
cd "$SCRIPT_DIR/mirrlyengine"

for item in "${TARGETS[@]}"; do
    TARGET="${item%%:*}"
    JNI_ABI="${item##*:}"

    echo "---------------------------------------------"
    echo "Building for $TARGET -> $JNI_ABI..."
    echo "---------------------------------------------"
    cargo build --target "$TARGET" --release

    SRC="$SCRIPT_DIR/mirrlyengine/target/$TARGET/release/libmirrlyengine.so"
    DST_DIR="$SCRIPT_DIR/app/src/main/jniLibs/$JNI_ABI"
    mkdir -p "$DST_DIR"
    cp -f "$SRC" "$DST_DIR/libmirrlyengine.so"
    echo "Copied $SRC -> $DST_DIR/libmirrlyengine.so"
done

cd "$SCRIPT_DIR"

echo "============================================="
echo "All 4 JNI libraries successfully built and verified!"
echo "============================================="
