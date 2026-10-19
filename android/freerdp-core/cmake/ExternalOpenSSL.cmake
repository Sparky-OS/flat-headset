set(KDECONNECT_NATIVE_JOBS 4 CACHE STRING "Concurrent jobs for native dependencies")

# OpenSSL build override for FreeRDP's Android client.
# SPDX-FileCopyrightText: FreeRDP contributors
# SPDX-FileCopyrightText: 2026 KDE Connect Developers
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0.
# KDE Connect stores the very verbose ExternalProject output in CMake log files so a later native
# compiler error does not make Gradle retain several megabytes of unrelated OpenSSL commands.

include(ExternalProject)
include(DepVersions)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(OSSL_ARCH "android-arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(OSSL_ARCH "android-arm")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(OSSL_ARCH "android-x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
  set(OSSL_ARCH "android-x86")
elseif(ANDROID_ABI STREQUAL "riscv64")
  set(OSSL_ARCH "android-riscv64")
else()
  message(FATAL_ERROR "ExternalOpenSSL: unsupported ABI '${ANDROID_ABI}'")
endif()

set(NDK_TOOLCHAIN_BIN "${NDK_ROOT}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin")

# Our release wrapper enables Lean Code in a disposable source tree.
set(FLAT_HEADSET_LEAN_COMMAND ${CMAKE_COMMAND} -E true)
if(DEFINED ENV{FLAT_HEADSET_LEAN_TOOL})
  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  set(FLAT_HEADSET_LEAN_COMMAND ${Python3_EXECUTABLE} "$ENV{FLAT_HEADSET_LEAN_TOOL}"
      --root <SOURCE_DIR> --component openssl-${ANDROID_ABI} --evidence "$ENV{FLAT_HEADSET_LEAN_EVIDENCE}")
endif()

ExternalProject_Add(
  openssl
  DOWNLOAD_EXTRACT_TIMESTAMP OFF
  SOURCE_DIR ${CMAKE_BINARY_DIR}/external/openssl-source
  BINARY_DIR ${CMAKE_BINARY_DIR}/external/openssl
  URL https://github.com/openssl/openssl/releases/download/${OPENSSL_VERSION}/${OPENSSL_VERSION}.tar.gz
  URL_HASH ${OPENSSL_HASH}
  PATCH_COMMAND ${FLAT_HEADSET_LEAN_COMMAND}
  CONFIGURE_COMMAND
    ${CMAKE_COMMAND} -E env PATH=${NDK_TOOLCHAIN_BIN}:$ENV{PATH} ANDROID_NDK=${NDK_ROOT} ANDROID_NDK_ROOT=${NDK_ROOT}
    ANDROID_NDK_HOME=${NDK_ROOT} CC=clang perl <SOURCE_DIR>/Configure ${OSSL_ARCH} shared no-tests no-apps no-docs
    -U__ANDROID_API__ -D__ANDROID_API__=${NDK_API_LEVEL} --prefix=${DEPS_INSTALL_DIR} --libdir=${CMAKE_INSTALL_LIBDIR}
  BUILD_COMMAND ${CMAKE_COMMAND} -E env PATH=${NDK_TOOLCHAIN_BIN}:$ENV{PATH} ANDROID_NDK=${NDK_ROOT}
                ANDROID_NDK_ROOT=${NDK_ROOT} ANDROID_NDK_HOME=${NDK_ROOT} make -j${KDECONNECT_NATIVE_JOBS} SHLIB_EXT=.so build_libs
  INSTALL_COMMAND
    ${CMAKE_COMMAND} -E env PATH=${NDK_TOOLCHAIN_BIN}:$ENV{PATH} ANDROID_NDK=${NDK_ROOT} ANDROID_NDK_ROOT=${NDK_ROOT}
    ANDROID_NDK_HOME=${NDK_ROOT} make -j${KDECONNECT_NATIVE_JOBS} SHLIB_EXT=.so install_sw
  COMMAND ${CMAKE_COMMAND} -E copy_directory
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/ossl-modules ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}
  LOG_CONFIGURE ON
  LOG_BUILD ON
  LOG_INSTALL ON
  BUILD_BYPRODUCTS
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libssl.so
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libcrypto.so
)
