#!/usr/bin/env bash
set -euo pipefail

########################################################
# build-fftw-android.sh
# Downloads FFTW sources and builds static libfftw3.a for Android ABIs and installs into:
#   <OUT_BASE>/<android_abi>/(include|lib)
#
# Defaults:
#  - Path to Android NDK: ANDROID_NDK_HOME
#  - Output install root: ./fftw-android
#  - FFTW version: 3.3.10
#  - API: 26
#  - ABIS: arm64, armv7-a, x86_64, x86
#
# Usage:
#   ./build-fftw-android.sh
#   ./build-fftw-android.sh --ndk /path/to/ndk --out /tmp/out --version 3.3.10 --api 26
########################################################

# Defaults
FFTW_VERSION="${FFTW_VERSION:-3.3.10}"
FFTW_BASE_URL="${FFTW_BASE_URL:-https://www.fftw.org}"
FFTW_TARBALL="fftw-${FFTW_VERSION}.tar.gz"
FFTW_URL="${FFTW_BASE_URL}/${FFTW_TARBALL}"
OUT_BASE_DEFAULT="$(pwd)/fftw-android"
OUT_BASE="${OUT_BASE:-$OUT_BASE_DEFAULT}"
API="${API:-26}"

DEFAULT_ARCHS=("arm64" "armv7-a" "x86_64" "x86")
ARCHS=("${DEFAULT_ARCHS[@]}")

NDK_DIR="${ANDROID_NDK_HOME:-}"

usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  --ndk PATH         Path to Android NDK (or set ANDROID_NDK_HOME)
  --out PATH         Output install root (default: ${OUT_BASE_DEFAULT})
  --version VER      FFTW version (default: ${FFTW_VERSION})
  --api N            Android API level used for clang (default: ${API})
  --archs a,b,c      Comma-separated subset of: ${DEFAULT_ARCHS[*]}
  --help             Show this help
EOF
  exit 1
}

# Parse CLI
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ndk) NDK_DIR="$2"; shift 2;;
    --out) OUT_BASE="$2"; shift 2;;
    --version) FFTW_VERSION="$2"; FFTW_TARBALL="fftw-${FFTW_VERSION}.tar.gz"; FFTW_URL="${FFTW_BASE_URL}/${FFTW_TARBALL}"; shift 2;;
    --api) API="$2"; shift 2;;
    --archs) IFS=',' read -r -a ARCHS <<< "$2"; shift 2;;
    --help|-h) usage;;
    *) echo "Unknown argument: $1"; usage;;
  esac
done

echo "FFTW version: ${FFTW_VERSION}"
echo "FFTW URL: ${FFTW_URL}"
echo "Output install root: ${OUT_BASE}"
echo "Android API: ${API}"
echo "Archs: ${ARCHS[*]}"
echo

# Validate NDK
if [[ -z "${NDK_DIR}" ]]; then
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_DIR="${ANDROID_NDK_HOME}"
  else
    echo "ERROR: Android NDK not detected. Set ANDROID_NDK_HOME or pass --ndk /path/to/android-ndk"
    exit 1
  fi
fi

NDK_DIR="$(realpath "${NDK_DIR}")"
PREBUILD="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"

if [[ ! -d "$PREBUILD" ]]; then
  echo "ERROR: NDK toolchain not found at $PREBUILD"
  exit 1
fi

mkdir -p "$OUT_BASE"
WORKDIR="$(pwd)/.build-fftw"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
cd "$WORKDIR"

TARBALL_PATH="${WORKDIR}/${FFTW_TARBALL}"
MD5_URL="${FFTW_URL}.md5sum"
MD5_PATH="${WORKDIR}/${FFTW_TARBALL}.md5sum"

# Download tarball if absent
if [[ -f "${TARBALL_PATH}" ]]; then
  echo "Tarball already exists: ${TARBALL_PATH}"
else
  echo "Downloading ${FFTW_URL} ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fSL -o "${TARBALL_PATH}" "${FFTW_URL}"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "${TARBALL_PATH}" "${FFTW_URL}"
  else
    echo "ERROR: need curl or wget to download sources"
    exit 1
  fi
fi

# Download md5sum
echo "Downloading MD5 checksum from ${MD5_URL} ..."
if command -v curl >/dev/null 2>&1; then
  curl -fSL -o "${MD5_PATH}" "${MD5_URL}"
elif command -v wget >/dev/null 2>&1; then
  wget -O "${MD5_PATH}" "${MD5_URL}"
fi

if [[ ! -f "${MD5_PATH}" ]]; then
  echo "ERROR: failed to download MD5 checksum from ${MD5_URL}"
  exit 1
fi

# Parse MD5 from file
EXPECTED_MD5="$(tr -d '\r\n' < "${MD5_PATH}" | sed -E 's/.*([a-fA-F0-9]{32}).*/\1/')"
if [[ -z "${EXPECTED_MD5}" ]]; then
  echo "ERROR: could not parse MD5 from ${MD5_PATH}"
  exit 1
fi

echo "Expected MD5: ${EXPECTED_MD5}"

# Compute local MD5
compute_md5() {
  command -v md5sum >/dev/null 2>&1 || { echo "ERROR: md5sum not found" >&2; return 1; }
  md5sum "$1" | awk '{print $1}'
}

LOCAL_MD5="$(compute_md5 "${TARBALL_PATH}")"
echo "Local  MD5: ${LOCAL_MD5}"

if [[ "${LOCAL_MD5,,}" != "${EXPECTED_MD5,,}" ]]; then
  echo "ERROR: MD5 mismatch! Download may be corrupted."
  rm -f "${TARBALL_PATH}"
  exit 1
fi
echo "MD5 verified OK."

# Extract
echo "Extracting ${TARBALL_PATH}..."
tar -xzf "${TARBALL_PATH}"
SRC_DIR="${WORKDIR}/fftw-${FFTW_VERSION}"
if [[ ! -d "${SRC_DIR}" ]]; then
  echo "ERROR: expected source dir ${SRC_DIR} not found after extraction"
  exit 1
fi

# mapping: arch key -> triple + android ABI
declare -A TRIPLE_MAP
declare -A ABI_MAP
TRIPLE_MAP["arm64"]="aarch64-linux-android"
ABI_MAP["arm64"]="arm64-v8a"

TRIPLE_MAP["armv7-a"]="armv7a-linux-androideabi"
ABI_MAP["armv7-a"]="armeabi-v7a"

TRIPLE_MAP["x86_64"]="x86_64-linux-android"
ABI_MAP["x86_64"]="x86_64"

TRIPLE_MAP["x86"]="i686-linux-android"
ABI_MAP["x86"]="x86"

# Tools from NDK
AR="$PREBUILD/bin/llvm-ar"
RANLIB="$PREBUILD/bin/llvm-ranlib"
STRIP="$PREBUILD/bin/llvm-strip"

CPU_COUNT="$(nproc || echo 1)"

echo
echo "=== Building FFTW for ABIs: ${ARCHS[*]} ==="
echo

for ARCH in "${ARCHS[@]}"; do
  if [[ -z "${TRIPLE_MAP[$ARCH]:-}" ]]; then
    echo "Skipping unknown arch: ${ARCH}"
    continue
  fi

  TRIPLE="${TRIPLE_MAP[$ARCH]}"
  ANDROID_ABI="${ABI_MAP[$ARCH]}"
  echo ">>> Building for ${ARCH} (ABI=${ANDROID_ABI}, triple=${TRIPLE}, API=${API})"

  TOOLCHAIN="$PREBUILD"
  SYSROOT="$TOOLCHAIN/sysroot"

  export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
  export CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export AS="$TOOLCHAIN/bin/llvm-as"
  export LD="$TOOLCHAIN/bin/ld.lld"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"

  base_cflags="--sysroot=${SYSROOT} -O2 -ffunction-sections -fdata-sections -fvisibility=hidden -fPIC -fomit-frame-pointer"
  LTO_FLAGS="-flto=thin"

  CFLAGS="${base_cflags} ${LTO_FLAGS}"
  CXXFLAGS="${CFLAGS}"
  LDFLAGS="-Wl,--gc-sections ${LTO_FLAGS} -Wl,-z,relro -Wl,-z,now"

  export CFLAGS CXXFLAGS LDFLAGS

  INSTALL_DIR="${OUT_BASE}/${ANDROID_ABI}"
  BUILD_DIR="${WORKDIR}/build-${ANDROID_ABI}"
  rm -rf "${BUILD_DIR}"
  mkdir -p "${BUILD_DIR}"
  mkdir -p "${INSTALL_DIR}"

  pushd "${BUILD_DIR}" >/dev/null

  CONFIG_CMD=(
    "${SRC_DIR}/configure"
    --host="${TRIPLE}"
    --prefix="${INSTALL_DIR}"
    LIBS="-lc"
    --enable-static
    --disable-shared
    --disable-fortran
    --disable-maintainer-mode
    --disable-debug
  )

  echo "Configuring: ${CONFIG_CMD[*]}"
  "${CONFIG_CMD[@]}"

  echo "make -j${CPU_COUNT}"
  make -j"${CPU_COUNT}"
  make install

  echo "Stripping static libs in ${INSTALL_DIR}/lib ..."
  set +e
  $STRIP --strip-unneeded "${INSTALL_DIR}/lib/"*.a 2>/dev/null || true
  set -e

  popd >/dev/null

  # Cleanup build dir
  rm -rf "${BUILD_DIR}"

  echo "Installed ABI ${ANDROID_ABI} -> ${INSTALL_DIR}"
  echo
done

# Cleanup sources and tarball
rm -rf "${SRC_DIR}"
rm -f "${TARBALL_PATH}" "${MD5_PATH}"
# Remove workdir (it should be empty now)
rmdir "${WORKDIR}" >/dev/null 2>&1 || true

echo "FFTW compiled. Install root: ${OUT_BASE}"
