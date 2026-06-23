#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build_daemon_android"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DCMAKE_BUILD_TYPE=Release

cmake --build "$BUILD_DIR" --target display_daemon -j$(nproc)

echo "Built: $BUILD_DIR/display_daemon"
