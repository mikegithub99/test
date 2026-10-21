#!/usr/bin/env bash
# Build and install 32-bit hiredis independently on RHEL 8 x86_64.
set -Eeuo pipefail

PREFIX="${PREFIX:-/opt/redis32}"
HIREDIS_VERSION="${HIREDIS_VERSION:-1.2.0}"
BUILD_ROOT="${BUILD_ROOT:-/usr/local/src/redis32-build}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"
SRC_DIR="$BUILD_ROOT/hiredis-$HIREDIS_VERSION"
BUILD_DIR="$SRC_DIR/build32"

[[ "$(uname -m)" == "x86_64" ]] || { echo "ERROR: requires x86_64." >&2; exit 1; }
[[ $EUID -eq 0 ]] || { echo "ERROR: run as root (sudo $0)." >&2; exit 1; }
command -v dnf >/dev/null || { echo "ERROR: dnf not found; intended for RHEL 8." >&2; exit 1; }

dnf install -y gcc make cmake curl tar gzip glibc-devel.i686 libgcc.i686

mkdir -p "$BUILD_ROOT" "$PREFIX"
if [[ ! -d "$SRC_DIR" ]]; then
  curl -fL --retry 3 "https://github.com/redis/hiredis/archive/refs/tags/v${HIREDIS_VERSION}.tar.gz" \
    -o "$BUILD_ROOT/hiredis-$HIREDIS_VERSION.tar.gz"
  tar -xzf "$BUILD_ROOT/hiredis-$HIREDIS_VERSION.tar.gz" -C "$BUILD_ROOT"
fi

cmake -S "$SRC_DIR" -B "$BUILD_DIR" \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-m32" \
  -DCMAKE_SHARED_LINKER_FLAGS="-m32" \
  -DCMAKE_EXE_LINKER_FLAGS="-m32" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DDISABLE_TESTS=ON

cmake --build "$BUILD_DIR" --parallel "$JOBS"
cmake --install "$BUILD_DIR"

echo "hiredis $HIREDIS_VERSION installed under $PREFIX"
echo "Headers: $PREFIX/include"
echo "Libraries (depending on CMake install layout): $PREFIX/lib or $PREFIX/lib64"
