#!/usr/bin/env bash
# Build redis-plus-plus separately, using an already-installed 32-bit hiredis.
set -Eeuo pipefail

PREFIX="${PREFIX:-/opt/redis32}"
HIREDIS_PREFIX="${HIREDIS_PREFIX:-$PREFIX}"
REDIS_PLUS_PLUS_VERSION="${REDIS_PLUS_PLUS_VERSION:-1.3.15}"
BUILD_ROOT="${BUILD_ROOT:-/usr/local/src/redis32-build}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"
SRC_DIR="$BUILD_ROOT/redis-plus-plus-$REDIS_PLUS_PLUS_VERSION"
BUILD_DIR="$SRC_DIR/build32"

[[ "$(uname -m)" == "x86_64" ]] || { echo "ERROR: requires x86_64." >&2; exit 1; }
[[ $EUID -eq 0 ]] || { echo "ERROR: run as root (sudo $0)." >&2; exit 1; }
command -v dnf >/dev/null || { echo "ERROR: dnf not found; intended for RHEL 8." >&2; exit 1; }

# Require hiredis to be installed first. Allow either lib or lib64 layouts.
HIREDIS_INCLUDE="$HIREDIS_PREFIX/include"
HIREDIS_HEADER="$HIREDIS_INCLUDE/hiredis"
HIREDIS_LIB=""
for candidate in "$HIREDIS_PREFIX/lib/libhiredis.so" "$HIREDIS_PREFIX/lib64/libhiredis.so" \
                 "$HIREDIS_PREFIX/lib/libhiredis.a" "$HIREDIS_PREFIX/lib64/libhiredis.a"; do
  if [[ -f "$candidate" ]]; then HIREDIS_LIB="$candidate"; break; fi
done
if [[ ! -d "$HIREDIS_HEADER" || -z "$HIREDIS_LIB" ]]; then
  echo "ERROR: could not find hiredis headers/library under $HIREDIS_PREFIX." >&2
  echo "Run separate/install_hiredis.sh first, or set HIREDIS_PREFIX." >&2
  exit 1
fi

dnf install -y gcc gcc-c++ make cmake git curl tar gzip glibc-devel.i686 libstdc++-devel.i686

mkdir -p "$BUILD_ROOT" "$PREFIX"
if [[ ! -d "$SRC_DIR" ]]; then
  curl -fL --retry 3 "https://github.com/sewenew/redis-plus-plus/archive/refs/tags/${REDIS_PLUS_PLUS_VERSION}.tar.gz" \
    -o "$BUILD_ROOT/redis-plus-plus-$REDIS_PLUS_PLUS_VERSION.tar.gz"
  tar -xzf "$BUILD_ROOT/redis-plus-plus-$REDIS_PLUS_PLUS_VERSION.tar.gz" -C "$BUILD_ROOT"
fi

# Explicit -D settings point redis-plus-plus at the independently installed hiredis.
cmake -S "$SRC_DIR" -B "$BUILD_DIR" \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_STANDARD=17 \
  -DCMAKE_CXX_FLAGS="-m32 -D_GLIBCXX_USE_CXX11_ABI=0" \
  -DCMAKE_C_FLAGS="-m32" \
  -DCMAKE_SHARED_LINKER_FLAGS="-m32" \
  -DCMAKE_EXE_LINKER_FLAGS="-m32" \
  -DCMAKE_PREFIX_PATH="$HIREDIS_PREFIX" \
  -DCMAKE_INCLUDE_PATH="$HIREDIS_INCLUDE" \
  -DCMAKE_LIBRARY_PATH="$HIREDIS_PREFIX/lib;$HIREDIS_PREFIX/lib64" \
  -DREDIS_PLUS_PLUS_HIREDIS_HEADER="$HIREDIS_HEADER" \
  -DREDIS_PLUS_PLUS_HIREDIS_LIB="$HIREDIS_LIB" \
  -DREDIS_PLUS_PLUS_BUILD_TEST=OFF \
  -DREDIS_PLUS_PLUS_BUILD_STATIC=OFF

cmake --build "$BUILD_DIR" --parallel "$JOBS"
cmake --install "$BUILD_DIR"

echo "redis-plus-plus $REDIS_PLUS_PLUS_VERSION installed under $PREFIX"
