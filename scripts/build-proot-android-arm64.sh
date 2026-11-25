#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROOT_COMMIT="6f8ebfd8e24887dfba64c3f2d7d5fe9dc059b60e"
PROOT_SOURCE_DIR="${PROOT_SOURCE_DIR:-$ROOT_DIR/vendor/proot-static}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/28.2.13676358}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/build/proot-arm64}"
ARTIFACT_DIR="$OUT_DIR/artifacts"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

if [[ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    echo "Android NDK r28c was not found at: $ANDROID_NDK_HOME" >&2
    echo "Set ANDROID_NDK_HOME to an installed NDK 28.2.13676358 directory." >&2
    exit 1
fi

if [[ ! -d "$PROOT_SOURCE_DIR/.git" ]]; then
    mkdir -p "$(dirname "$PROOT_SOURCE_DIR")"
    git clone https://github.com/berilevi/proot-static.git "$PROOT_SOURCE_DIR"
fi

git -C "$PROOT_SOURCE_DIR" fetch --depth 1 origin "$PROOT_COMMIT"
git -C "$PROOT_SOURCE_DIR" checkout --detach "$PROOT_COMMIT"

mkdir -p "$ARTIFACT_DIR" "$JNI_DIR"
NDK="$ANDROID_NDK_HOME" \
OUT="$OUT_DIR/work" \
REPO_ARTIFACT_DIR="$ARTIFACT_DIR" \
bash "$PROOT_SOURCE_DIR/tools/build-static-aarch64.sh"

install -m 0755 "$ARTIFACT_DIR/proot" "$JNI_DIR/libfxforge_proot.so"
install -m 0755 "$ARTIFACT_DIR/loader" "$JNI_DIR/libfxforge_proot_loader.so"
install -m 0755 "$ARTIFACT_DIR/loader32" "$JNI_DIR/libfxforge_proot_loader32.so"

file "$JNI_DIR/libfxforge_proot.so"
readelf -h "$JNI_DIR/libfxforge_proot.so"
sha256sum "$JNI_DIR"/libfxforge_proot*.so
