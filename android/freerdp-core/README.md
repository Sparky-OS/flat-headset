<!--
SPDX-FileCopyrightText: 2026 KDE Connect Developers
SPDX-License-Identifier: CC0-1.0
-->

# FreeRDP Android library wrapper

This module builds the Android library from the upstream
[FreeRDP repository](https://github.com/FreeRDP/FreeRDP). The exact upstream
revision and source-archive SHA-256 are declared in `build.gradle.kts`; the
currently pinned revision is
[`89d8b7a1ae3b376cf77e5f85895e003e169b4cf5`](https://github.com/FreeRDP/FreeRDP/commit/89d8b7a1ae3b376cf77e5f85895e003e169b4cf5).
Gradle downloads that revision's archive and verifies it before use. No
unmodified FreeRDP source tree is vendored in this repository.

FreeRDP contains an Android library project at
[`client/Android/Studio/freeRDPCore`](https://github.com/FreeRDP/FreeRDP/tree/89d8b7a1ae3b376cf77e5f85895e003e169b4cf5/client/Android/Studio/freeRDPCore),
but upstream does not publish that module as a Maven artifact. Consequently
this wrapper currently has to compile the native libraries from the pinned
source.

## KDE Connect changes

`patches/` is an ordered `git format-patch` series on top of the pinned
revision. Gradle applies the complete series with `git apply`; the standalone
`patch` program is not required. The changes adapt FreeRDP's Android UI and
session lifecycle to KDE Connect's embedded remote-desktop client.

Building the native dependencies requires Git, Make, Perl, `pkg-config`, and
NASM on the host in addition to the Android SDK, NDK, and CMake. Git is also
used by FreeRDP's OpenH264 external-project build, independently of the KDE
Connect patch series.

The files in `cmake/` started as copies of the same-named files in upstream's
[`client/Android/cmake/`](https://github.com/FreeRDP/FreeRDP/tree/89d8b7a1ae3b376cf77e5f85895e003e169b4cf5/client/Android/cmake)
directory at the pinned revision. They remain under FreeRDP's Apache-2.0
license and contain these KDE Connect-specific changes:

- `ExternalFreeRDP.cmake` disables unused channels and dependencies and makes
  the native libraries visible to Android Gradle Plugin packaging;
- `ExternalOpenH264.cmake` keeps the Android C++ runtime linkage consistent and
  declares the generated library;
- `ExternalOpenSSL.cmake` makes the install command portable, declares its
  generated libraries, and keeps verbose ExternalProject output in log files.

`src/main/AndroidManifest.xml` replaces the standalone aFreeRDP application
manifest with the non-exported component surface embedded by KDE Connect.

## Updating FreeRDP

1. Change `freeRdpCommit` and `freeRdpArchiveSha256` in `build.gradle.kts`.
2. Rebase the patches in `patches/` onto that revision, preserving their
   numeric order.
3. Rebase the three CMake overrides onto their upstream counterparts and keep
   the KDE Connect deltas described above.
4. Run `./gradlew :freerdp-core:assembleDebug testDebug` and test an RDP session
   on every supported ABI.

The native dependencies and their licenses are listed in
`../licenses/rdp-native-notices.txt`.
