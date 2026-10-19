# FreeRDP: A Remote Desktop Protocol Implementation
# SPDX-FileCopyrightText: FreeRDP contributors
# SPDX-FileCopyrightText: 2026 KDE Connect Developers
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0.
# KDE Connect override: keep the upstream Android build but omit redirection channels that are
# neither exposed by this integration nor available on KDE Connect's Android 6 minimum API.

include(ExternalProject)

find_package(PkgConfig REQUIRED)

set(FREERDP_PREFIX_PATH
    "${DEPS_INSTALL_DIR};${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/cmake;${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR};${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/pkgconfig;${DEPS_INSTALL_DIR};${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_DATAROOTDIR}"
)

set(FREERDP_EXTRA_CMAKE_ARGS
    -DCMAKE_C_FLAGS:STRING=${CMAKE_C_FLAGS}
    -DWITHOUT_WINPR_3x_DEPRECATED:BOOL=ON
    -DWITHOUT_FREERDP_3x_DEPRECATED:BOOL=ON
    -DWITH_CLIENT_SDL:BOOL=OFF
    -DWITH_SAMPLE:BOOL=OFF
    -DWITH_SERVER:BOOL=OFF
    -DWITH_MANPAGES:BOOL=OFF
    -DWITH_SYSTEMD:BOOL=OFF
    -DPKG_CONFIG_EXECUTABLE:FILEPATH=${PKG_CONFIG_EXECUTABLE}
    -DWITH_INTERNAL_RC4:BOOL=ON
    -DWITH_INTERNAL_MD4:BOOL=ON
    -DWITH_INTERNAL_MD5:BOOL=ON
    -DWITH_OPENSSL:BOOL=${WITH_OPENSSL}
    -DWITH_AAD:BOOL=OFF
    -DWITH_MEDIACODEC:BOOL=${WITH_MEDIACODEC}
    -DWITH_FFMPEG:BOOL=${WITH_FFMPEG}
    -DWITH_SWSCALE:BOOL=${WITH_FFMPEG}
    -DWITH_OPENH264:BOOL=${WITH_OPENH264}
    -DWITH_VAAPI_H264_ENCODING:BOOL=OFF
    -DWINPR_UTILS_IMAGE_PNG:BOOL=${WITH_PNG}
    -DWINPR_UTILS_IMAGE_WEBP:BOOL=${WITH_WEBP}
    -DWINPR_UTILS_IMAGE_JPEG:BOOL=${WITH_JPEG}
    -DWITH_OPUS:BOOL=${WITH_OPUS}
    -DWITH_CJSON_REQUIRED:BOOL=${WITH_CJSON}
    -DCHANNEL_PRINTER_CLIENT:BOOL=OFF
    -DCHANNEL_RDPECAM_CLIENT:BOOL=OFF
)

if(FREERDP_NATIVE_OPTIMIZED)
  # Keep the APK debuggable and native symbols, but measure the decoder at its
  # normal optimization level instead of benchmarking an unoptimized Debug build.
  list(APPEND FREERDP_EXTRA_CMAKE_ARGS "-DCMAKE_C_FLAGS_DEBUG:STRING=-O2 -g")
endif()

if(NOT WITH_CJSON)
  # FreeRDP otherwise auto-detects any cJSON left in the shared dependency
  # prefix, even when it is not required.
  list(APPEND FREERDP_EXTRA_CMAKE_ARGS -DWITH_JSON_DISABLED:BOOL=ON)
endif()

ExternalProject_Add(
  freerdp
  DOWNLOAD_EXTRACT_TIMESTAMP OFF
  SOURCE_DIR ${FREERDP_SOURCE_DIR}
  CMAKE_ARGS ${ANDROID_CMAKE_ARGS} -DCMAKE_INSTALL_PREFIX:PATH=${DEPS_INSTALL_DIR}
             -DCMAKE_INSTALL_LIBDIR:STRING=${CMAKE_INSTALL_LIBDIR} -DCMAKE_PREFIX_PATH:PATH=${FREERDP_PREFIX_PATH}
             ${FREERDP_EXTRA_CMAKE_ARGS}
  DEPENDS ${ANDROID_NATIVE_DEPS}
  BUILD_BYPRODUCTS
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libfreerdp3.so
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libfreerdp-client3.so
    ${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libwinpr3.so
)

file(MAKE_DIRECTORY ${DEPS_INSTALL_DIR}/include ${DEPS_INSTALL_DIR}/include/freerdp3 ${DEPS_INSTALL_DIR}/include/winpr3)

add_library(freerdp3-lib SHARED IMPORTED GLOBAL)
set_target_properties(
  freerdp3-lib PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libfreerdp3.so"
                          INTERFACE_INCLUDE_DIRECTORIES "${DEPS_INSTALL_DIR}/include/freerdp3"
)
add_dependencies(freerdp3-lib freerdp)

# ExternalProject libraries are invisible to Android Gradle Plugin's native
# dependency scanner. Model the non-system ELF dependencies explicitly so the
# corresponding .so files are packaged in the AAR/APK.
if(WITH_OPENSSL)
  add_library(kdeconnect-openssl-ssl SHARED IMPORTED GLOBAL)
  set_target_properties(
    kdeconnect-openssl-ssl PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libssl.so"
  )
  add_dependencies(kdeconnect-openssl-ssl openssl)

  add_library(kdeconnect-openssl-crypto SHARED IMPORTED GLOBAL)
  set_target_properties(
    kdeconnect-openssl-crypto PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libcrypto.so"
  )
  add_dependencies(kdeconnect-openssl-crypto openssl)

  set_property(
    TARGET freerdp3-lib APPEND PROPERTY INTERFACE_LINK_LIBRARIES
                                           kdeconnect-openssl-ssl kdeconnect-openssl-crypto
  )
endif()

if(WITH_OPENH264)
  add_library(kdeconnect-openh264 SHARED IMPORTED GLOBAL)
  set_target_properties(
    kdeconnect-openh264 PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libopenh264.so"
  )
  add_dependencies(kdeconnect-openh264 openh264)
  set_property(TARGET freerdp3-lib APPEND PROPERTY INTERFACE_LINK_LIBRARIES kdeconnect-openh264)
endif()

add_library(freerdp-client3-lib SHARED IMPORTED GLOBAL)
set_target_properties(
  freerdp-client3-lib PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libfreerdp-client3.so"
                                 INTERFACE_INCLUDE_DIRECTORIES "${DEPS_INSTALL_DIR}/include/freerdp3"
)
add_dependencies(freerdp-client3-lib freerdp)

add_library(winpr3-lib SHARED IMPORTED GLOBAL)
set_target_properties(
  winpr3-lib PROPERTIES IMPORTED_LOCATION "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_LIBDIR}/libwinpr3.so"
                        INTERFACE_INCLUDE_DIRECTORIES "${DEPS_INSTALL_DIR}/include/winpr3"
)
add_dependencies(winpr3-lib freerdp)
