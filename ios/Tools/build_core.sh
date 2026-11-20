#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
mode=${1:?Usage: build_core.sh ios|host SOURCE_ROOT BUILD_ROOT}
sources=$(cd "${2:?Source root required}" && pwd)
mkdir -p "${3:?Build root required}"
build=$(cd "$3" && pwd)
prefix="$build/install"
core_project=$(cd "$(dirname "$0")/../Core" && pwd)
mkdir -p "$build/openssl" "$build/ffmpeg" "$prefix"
cmake_args=()
ffmpeg_args=()
openssl_args=()
case "$mode" in
  ios)
    [[ $(uname -s) == Darwin ]] || { echo 'The iPhone core build requires the hosted Mac and its SDK.' >&2; exit 1; }
    sdk=$(xcrun --sdk iphoneos --show-sdk-path)
    cc=$(xcrun --sdk iphoneos --find clang)
    openssl_target=ios64-xcrun
    openssl_args=(-miphoneos-version-min=16.0)
    ffmpeg_args=(--enable-cross-compile --target-os=darwin --arch=arm64 --cc="$cc"
      --sysroot="$sdk" "--extra-cflags=-arch arm64 -miphoneos-version-min=16.0"
      "--extra-ldflags=-arch arm64 -miphoneos-version-min=16.0"
      --enable-videotoolbox --enable-hwaccel=h264_videotoolbox --enable-hwaccel=hevc_videotoolbox)
    cmake_args=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_SYSTEM_PROCESSOR=arm64
      -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_SYSROOT="$sdk"
      -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DIOS_TARGET_SDK=16.0
      "-DCMAKE_FIND_ROOT_PATH=$prefix;$sdk"
      -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY
      -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER
      '-DCMAKE_IGNORE_PREFIX_PATH=/opt/homebrew;/usr/local;/opt/local'
      -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO)
    ;;
  host)
    [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Host proof currently targets Linux x86_64.' >&2; exit 1; }
    openssl_target=linux-x86_64
    ffmpeg_args=(--cc=clang)
    cmake_args=(-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++)
    ;;
  *) echo 'Expected ios or host.' >&2; exit 1 ;;
esac
(
  cd "$build/openssl"
  perl "$sources/openssl/Configure" "$openssl_target" no-shared no-tests no-apps no-docs \
    --prefix="$prefix" --libdir=lib "${openssl_args[@]}"
  make -j4 build_sw
  make install_sw
)
(
  cd "$build/ffmpeg"
  "$sources/ffmpeg/configure" --prefix="$prefix" --libdir="$prefix/lib" \
    --disable-gpl --enable-static --disable-shared --enable-pic --disable-x86asm \
    --disable-programs --disable-doc --disable-debug --disable-network --disable-autodetect \
    --disable-everything --disable-avdevice --disable-avformat --disable-avfilter \
    --disable-swresample --enable-swscale --enable-avcodec --enable-avutil \
    --enable-decoder=h264 --enable-parser=h264 --enable-decoder=hevc --enable-parser=hevc \
    "${ffmpeg_args[@]}"
  grep -Fx '#define CONFIG_GPL 0' config.h
  grep -Fx '#define CONFIG_NONFREE 0' config.h
  grep -Fx '#define CONFIG_H264_DECODER 1' config_components.h
  grep -Fx '#define CONFIG_HEVC_DECODER 1' config_components.h
  if [[ "$mode" == ios ]]; then
    grep -Fx '#define CONFIG_VIDEOTOOLBOX 1' config.h
    grep -Fx '#define CONFIG_H264_VIDEOTOOLBOX_HWACCEL 1' config_components.h
    grep -Fx '#define CONFIG_HEVC_VIDEOTOOLBOX_HWACCEL 1' config_components.h
  fi
  make -j4
  make install
)
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
export PKG_CONFIG_PATH=
cmake -S "$core_project" -B "$build/freerdp" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR=lib \
  -DCMAKE_PREFIX_PATH="$prefix" -DOPENSSL_ROOT_DIR="$prefix" \
  -DFREERDP_SOURCE_DIR="$sources/freerdp" "${cmake_args[@]}"
grep -Fx '#define WITH_VIDEO_FFMPEG' "$build/freerdp/freerdp/include/freerdp/config.h"
if [[ "$mode" == ios ]]; then
  grep -Fx '#define WITH_VIDEOTOOLBOX' "$build/freerdp/freerdp/include/freerdp/config.h"
fi
cmake --build "$build/freerdp" --parallel 4
cmake --install "$build/freerdp"
if [[ "$mode" == host ]]; then
  ctest --test-dir "$build/freerdp" --output-on-failure
else
  xcrun lipo "$build/freerdp/core-link-check" -verify_arch arm64
  xcrun otool -l "$build/freerdp/core-link-check" > "$build/core-link-load-commands.txt"
  if grep -q LC_CODE_SIGNATURE "$build/core-link-load-commands.txt"; then
    echo 'The core link probe unexpectedly contains a signature.' >&2
    exit 1
  fi
  for library in "$prefix"/lib/*.a; do
    xcrun lipo "$library" -verify_arch arm64
  done
fi
