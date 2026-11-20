# Flat Headset iPhone core

This build prepares static FreeRDP, OpenSSL and FFmpeg libraries for the native receiver.
It does not yet connect the Metal proof app to an RDP session.

`sources.json` pins FreeRDP 3.28.0 and the OpenSSL 4.0.1 / FFmpeg n8.1 archives named by that revision's `cmake/DepVersions.cmake`.
The fetcher verifies SHA-256 before extraction.
No Apple SDK is downloaded; the iPhone build runs on the standard hosted macos-26 image with its default Xcode and SDK.
The workflow prints their versions and requires Xcode 26 or newer.

Run from the repository root on that Mac:

```sh
python3 ios/Tools/fetch_core_sources.py "$RUNNER_TEMP/flat-headset-core-sources"
bash ios/Tools/build_core.sh ios "$RUNNER_TEMP/flat-headset-core-sources" "$RUNNER_TEMP/flat-headset-core"
```

For a Linux x86_64 container proof, use the same verified sources and `build_core.sh host SOURCES BUILD`.
The host mode builds the same pinned libraries and runs the context/codec lifecycle check without connecting to a server.
Only the iPhone mode compiles the VideoToolbox integration; the host proof cannot establish hardware decoding or on-phone behavior.
All builds use at most four jobs.

The core includes TLS/NLA primitives, client-common APIs, H.264/HEVC decoding and the drdynvc, rdpgfx, disp and cliprdr client channels.
Audio and device forwarding are excluded from this build; desktop audio remains on the PC.
FreeRDP's VideoToolbox path is experimental and may fall back internally; its presence is not proof of hardware decode, zero-copy or the receiver's eventual error behavior.
Pairing, certificate pinning, RDP connection lifecycle, Metal frame delivery and viewer profiles follow after this core builds on the hosted Mac.
Android's platform patches are not copied into this iPhone build.

The `FlatHeadset-core-arm64-COMMIT` artifact contains only third-party headers/libraries, their licenses, the link probe and build provenance, with a SHA-256 file.
It is a developer artifact, not an application or an IPA, and is never passed to the phone renewal helper.
The existing `FlatHeadset-unsigned-COMMIT` artifact remains the generated-picture app.

FreeRDP is Apache-2.0, OpenSSL is Apache-2.0, and this FFmpeg configuration disables GPL and nonfree components and uses LGPL-2.1-or-later terms.
Retain the exact corresponding sources and license obligations when these static libraries become part of a distributed application.
The probe explicitly disables RDP8 network-auto-detect/heartbeat/multitransport settings because upstream routes those through the excluded device-redirection channel.
That test configuration does not settle the eventual receiver connection policy.
