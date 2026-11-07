# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.13)
project(flat-headset-freerdp LANGUAGES C)

# AGP tracks included .cmake files only within this source root. Keep the
# embedding overrides here so native packaging metadata follows their changes.
if(NOT IS_DIRECTORY "${FLAT_HEADSET_FREERDP_CORE}/src/main/cpp")
  message(FATAL_ERROR "Prepare the pinned FreeRDP core before configuring")
endif()
add_subdirectory("${FLAT_HEADSET_FREERDP_CORE}/src/main/cpp" upstream)
