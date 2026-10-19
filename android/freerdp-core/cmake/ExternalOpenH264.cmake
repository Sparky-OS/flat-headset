set(KDECONNECT_NATIVE_JOBS 4 CACHE STRING "Concurrent jobs for native dependencies")

# FreeRDP: A Remote Desktop Protocol Implementation
# SPDX-FileCopyrightText: FreeRDP contributors
# SPDX-FileCopyrightText: 2026 KDE Connect Developers
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0.
# KDE Connect override: keep libc++ linkage consistent when OpenH264's install
# target regenerates its version header and relinks the shared library.

include(ExternalProject)
include(DepVersions)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(O264_ARCH "arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(O264_ARCH "arm")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(O264_ARCH "x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
  set(O264_ARCH "x86")
elseif(ANDROID_ABI STREQUAL "riscv64")
  set(O264_ARCH "riscv64")
else()
  message(FATAL_ERROR "ExternalOpenH264: unsupported ABI '${ANDROID_ABI}'")
endif()

set(OPENH264_MAKE_ARGS
    ENABLEPIC=Yes
    LDFLAGS=-static-libstdc++
    OS=android
    NDKROOT=${NDK_ROOT}
    NDK_TOOLCHAIN_VERSION=clang
    TARGET=android-${NDK_API_LEVEL}
    NDKLEVEL=${NDK_API_LEVEL}
    ARCH=${O264_ARCH}
)

# Our release wrapper enables Lean Code in a disposable source tree.
set(FLAT_HEADSET_LEAN_COMMAND ${CMAKE_COMMAND} -E true)
if(DEFINED ENV{FLAT_HEADSET_LEAN_TOOL})
  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  set(FLAT_HEADSET_LEAN_COMMAND ${Python3_EXECUTABLE} "$ENV{FLAT_HEADSET_LEAN_TOOL}"
      --root <SOURCE_DIR> --component openh264-${ANDROID_ABI} --evidence "$ENV{FLAT_HEADSET_LEAN_EVIDENCE}")
endif()

ExternalProject_Add(
  openh264
  DOWNLOAD_EXTRACT_TIMESTAMP OFF
  SOURCE_DIR ${CMAKE_BINARY_DIR}/external/openh264-source
  GIT_REPOSITORY https://github.com/cisco/openh264.git
  GIT_TAG ${OPENH264_TAG}
  GIT_SHALLOW TRUE
  PATCH_COMMAND git apply --3way ${FREERDP_SOURCE_DIR}/client/Android/cmake/0001-riscv64-support.patch
                ${FREERDP_SOURCE_DIR}/client/Android/cmake/0002-openh264-pkgconfig-patch.patch
  COMMAND ${FLAT_HEADSET_LEAN_COMMAND}
  CONFIGURE_COMMAND ${CMAKE_COMMAND} -E copy_directory_if_different <SOURCE_DIR> <BINARY_DIR>
  BUILD_COMMAND
    ${CMAKE_COMMAND} -E env "PATH=${NDK_ROOT}:$ENV{PATH}" make ${OPENH264_MAKE_ARGS} -j${KDECONNECT_NATIVE_JOBS} libraries
  INSTALL_COMMAND
    ${CMAKE_COMMAND} -E env "PATH=${NDK_ROOT}:$ENV{PATH}" make ${OPENH264_MAKE_ARGS}
    PREFIX=${DEPS_INSTALL_DIR} LIBDIR_NAME=${CMAKE_INSTALL_LIBDIR}
    SHAREDLIB_DIR=${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR} -j${KDECONNECT_NATIVE_JOBS} install
  BUILD_BYPRODUCTS ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libopenh264.so
)
