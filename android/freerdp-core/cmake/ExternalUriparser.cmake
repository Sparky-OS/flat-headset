# SPDX-FileCopyrightText: FreeRDP contributors
# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: Apache-2.0
include(ExternalProject)
include(DepVersions)

set(FLAT_HEADSET_LEAN_COMMAND ${CMAKE_COMMAND} -E true)
if(DEFINED ENV{FLAT_HEADSET_LEAN_TOOL})
  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  set(FLAT_HEADSET_LEAN_COMMAND ${Python3_EXECUTABLE} "$ENV{FLAT_HEADSET_LEAN_TOOL}"
      --root <SOURCE_DIR> --component uriparser-${ANDROID_ABI} --evidence "$ENV{FLAT_HEADSET_LEAN_EVIDENCE}")
endif()

ExternalProject_Add(
  uriparser
  DOWNLOAD_EXTRACT_TIMESTAMP OFF
  SOURCE_DIR ${CMAKE_BINARY_DIR}/external/uriparser-source
  URL https://github.com/uriparser/uriparser/releases/download/uriparser-${URIPARSER_VERSION}/uriparser-${URIPARSER_VERSION}.tar.xz
  URL_HASH ${URIPARSER_HASH}
  PATCH_COMMAND ${FLAT_HEADSET_LEAN_COMMAND}
  CMAKE_ARGS ${ANDROID_CMAKE_ARGS} -DCMAKE_INSTALL_PREFIX:PATH=${DEPS_INSTALL_DIR}
             -DCMAKE_INSTALL_LIBDIR:STRING=${CMAKE_INSTALL_LIBDIR} -DURIPARSER_BUILD_DOCS:BOOL=OFF
             -DURIPARSER_BUILD_TESTS:BOOL=OFF -DURIPARSER_BUILD_TOOLS:BOOL=OFF
)
