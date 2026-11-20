# Flat Headset on iPhone

This development target displays a generated left/right picture through Metal and provides a foreground Computers screen for KDE Connect pairing and a paired RDP session with a decoded-frame Metal view.
The received-desktop view is under development; viewer correction now follows the Android fitted shader and pinned Cardboard geometry, pending its hosted compile and optical check.
The generated image is a separate test picture, not evidence of a received desktop.
The left image has a cool background and an L mark; the right has a warm background and an R mark.
Both show their complete outer edges, a grid and a circle measured in pixels, with no texture rescaling.

The unsigned workflow uses the standard hosted macOS image's installed Xcode and prints its image/compiler/SDK versions before checking out the source.
It builds for arm64 iPhone with deployment target iOS 16.0, no signing team and `CODE_SIGNING_ALLOWED=NO`.
No Apple account, certificate, secret or separately downloaded SDK is used by the workflow.
The native shell uses UIKit, Foundation, Security, MetalKit and Metal; the transport links the eight pinned OpenSSL, FreeRDP/WinPR and FFmpeg static libraries.
The app target is `ios/FlatHeadset.xcodeproj`, shared scheme `FlatHeadset`.
Its display name remains **Flat Headset** in every language; its bundle ID is `org.sparkyos.flatheadset`.

To run the portable checks on Linux or macOS:

```sh
python3 ios/Tools/check_localizations.py
cmake -S ios -B build/ios-pattern -DCMAKE_BUILD_TYPE=Debug
cmake --build build/ios-pattern --parallel 4
ctest --test-dir build/ios-pattern --output-on-failure
```

Xcode compiles `Pattern.metal` into the application’s default Metal library.
The Objective-C++/Metal/UIKit portion requires Apple's SDK and is compiled only on the hosted Mac.
A passing host test does not prove the Metal render or a native phone launch.
The workflow's literal `xcodebuild` and packaging commands are in [ios-unsigned.yml](../.github/workflows/ios-unsigned.yml).

The artifact `FlatHeadset-unsigned-COMMIT` contains `FlatHeadset-unsigned.ipa`, `SHA256SUMS` and `flat-headset-artifact.json`.
Verify the downloaded IPA with `sha256sum -c SHA256SUMS` before creating the private installation manifest.
The JSON contains the receiver entry (`role`, `path`, `sha256`, `bundle_id`) for the three-program renewal helper.
When all three programs exist, combine their reviewed entries and add the intended phone's `udid` privately; this single-app artifact is not yet a complete renewal manifest.
No UDID, credentials or signing material belongs in the repository or CI artifacts.
Signing and installation occur separately on Linux with the person's free account; unsigned does not mean directly installable.

Native catalogs use `en`, `pl`, `pt-BR`, `fr`, `de`, `es`, `ja`, `ko`, `zh-Hans` and `zh-Hant`.
The checker fails on missing/extra/duplicate/empty keys, a missing language, or any changed product name.
The Computers screen, local-network permission explanation and Metal failure text are localized.
The generated L/R marks identify the test eyes; they are not localized menu words.

The forthcoming receiver retains the Android design: KDE Connect pairing controls a KRdp virtual monitor, FreeRDP receives its desktop, and the actual Cardboard viewer parameters supply lens correction.
The build proof is a step toward that receiver, not a substitute for it.

## Foreground pairing

The Computers screen discovers the desktop's `_kdeconnect._udp` Bonjour service and initiates its TCP connection.
The service's SRV port is the desktop's TCP port despite the service type's UDP suffix.
Only protocol 8 is accepted in this target.
Discovery names and addresses are candidates, never evidence of trust.
The TCP initiator acts as the TLS server, as KDE Connect's LAN protocol requires.
The certificate's device ID, signature, validity, key strength and saved SHA-256 pin are checked before accepting the encrypted identity.
The verification code follows the Android backend's sorted public-key hash with the pairing timestamp.
Confirm that both screens show the same code; remote acceptance alone does not save trust on this phone.
The identity and approved certificate pins live in the app's Keychain with `WhenUnlockedThisDeviceOnly` accessibility.
A changed certificate fails; forgetting a pairing is an explicit swipe action on the computer row.
Connections stop when the app leaves the foreground or the Computers screen closes.
After the verified pairing pin is saved, Catch requests a native-panel virtual monitor over that authenticated connection.
The peer must advertise protocol 2 virtual-monitor support and native-panel version 1.
Only the matching session ID, exact panel, NLA credentials and SHA-256 RDP certificate fingerprint authorize a connection.
The RDP address is the authenticated socket peer, never an address supplied in a reply.
External certificate management enforces that fingerprint even for a certificate trusted by a system CA.
RDP redirection, automatic reconnect, interactive credential prompts and weaker security negotiation are disabled.
Stop releases the session; forgetting pairing, closing the screen, losing the control connection or backgrounding aborts RDP.
A new Catch uses a fresh request and fresh credentials.
Refusals and timeouts are shown in the native language; remote error strings and credentials are not displayed.
Completed GDI paints publish owned BGRA frames to the desktop Metal view.
A connected-session status proves neither a displayed desktop nor lens correction.
The desktop view reports a localized failure if its first picture does not arrive within 45 seconds; the generated picture is never substituted for missing desktop frames.

The workflow now builds the app after its static dependencies in the `iphone-core` job on macos-26.
The `iphone` job retains the portable picture/catalog checks on macos-15.
For a manual native build, pass `FH_CORE_PREFIX` pointing at `build_core.sh`'s `install` directory.
The core is configured with OpenSSL `no-shared no-module no-legacy`; FreeRDP keeps its internal MD4, MD5 and RC4 implementations.
The Linux core build runs known-answer crypto checks, upstream's in-memory NTLM exchange test and mutual-TLS/pinning tests over AF_UNIX socketpairs.
The paired-session test checks grants, rejection paths, certificate pins and context teardown without a network connection.
These checks do not establish Bonjour discovery, Keychain behavior or interoperability with a live desktop on an iPhone.
Those require the native build and a later authorized device session.

Pairing behavior follows the KDE Connect Android `PairingHandler.kt`, `SslHelper.kt` and `LanLinkProvider.java` in this repository, and the desktop's `core/backends/pairinghandler.cpp` and `core/backends/lan/avahidiscovery.cpp`.
Credits: Albert Vaca Cintora, Vineet Garg, the KDE Connect contributors, The OpenSSL Project Authors, and Niels Lohmann.
The vendored JSON parser's version, source and hashes are recorded in `ThirdParty/nlohmann/PROVENANCE.json`; its MIT license is retained.

## Session adapter development

`Session/SessionControl` implements the desktop virtualmonitor protocol and owns revocable grants.
`Session/RdpSession` owns the FreeRDP context, worker, callbacks, event loop and GDI lifetime.
`Sources/Computers.mm` binds them to the Keychain-approved identity and foreground lifecycle.
The native-panel request uses physical landscape pixels, refresh rate and unknown physical dimensions (zero millimetres); `lensProfile` records the selected Android profile ID (`none`, `shinecon-fit-d` or `cardboard-default`).
It does not invent optics or change the transmitted eye layout.
Build the portable session adapter against the same pinned source versions with:

```sh
python3 ios/Tools/fetch_core_sources.py build/core-sources
bash ios/Tools/build_core.sh host build/core-sources build/core-host
```

Run those commands in the isolated Linux build environment with the prerequisites in the build script and four build jobs.
The session adapter and its installed-library/framework closure passed hosted run 44702615908.
The decoded-frame view and first strict VideoToolbox patch passed hosted run 44770385750.
The explicit software-choice callback and UI passed hosted run 44783586937; viewer correction is the next hosted compile.
No device runtime result is claimed here.

## Decoded frames to Metal

FreeRDP's H.264/AVC444 decoder uses FFmpeg and VideoToolbox on iPhone.
When VideoToolbox initialization or its frame format fails, the receiver hides the picture and explains the hardware-decoding failure.
It offers Continue with software decoding or Go back, explicitly warning that software is slower and uses more battery.
Only that choice enables software decoding in the existing paired session.
Stop, backgrounding or loss of pairing cancels a pending choice.
No design found for remembering the choice across sessions: each new session starts with hardware decoding.
It applies to the hash-pinned FreeRDP source before compilation and is included in the core artifact beside the source lock.
The patch does not change the host test decoder, and does not establish hardware operation on any phone.
The negotiated session currently advertises H.264, not HEVC; the compiled HEVC decoder alone is not an HEVC session claim.

FreeRDP transfers the decoded hardware image to CPU memory and reconstructs the desktop's BGRA GDI surface.
This integration copies each completed dirty paint into an owned frame, then uploads the latest frame to a shared Metal texture.
It is not a zero-copy path.
The producer keeps only one pending frame; replacing it cannot change a frame already being read.
At most two GPU commands are in flight, and each new texture stays immutable while the GPU uses it.
The fragment shader reads matching panel texels, with no eye rescaling, new separation or output-format choice.
A native extent mismatch fails visibly.
The ten-language status layer covers a missing picture and renderer failures.
Stop, session failure, control loss and backgrounding close the frame queue and hide the old picture.

The Linux checks exercise padded BGRA rows, both eye positions, invalid dimensions, concurrent ownership and latest-frame replacement.
A separate test allocates a real FreeRDP GDI surface, invokes the production paint handoff and checks forty complete pairs plus dirty-region reset and revocation.
These are memory and integration checks, not an encoded stream or a Metal render.
The native Metal view, decoding-choice UI and core callback passed hosted run 44783586937.
Both still require a later authorized device check.

The Linux decoder-choice analogue exercises both failure reasons and each action, verifies that pending frames disappear while consent is outstanding, and decodes the real H.264 fixture only after approval.
It also checks cancellation, repeated surfaces within the same session and a fresh session returning to hardware by default.
A connection deadline is held while a decoder decision is outstanding, then returns to its normal bound.
No time spent waiting is treated as consent.

For a Linux syntax check of the desktop, computer and viewer-settings controllers without Apple headers, run `bash ios/Tools/check_objc_syntax.sh`.
See [the authored test doubles](Tests/ObjCStubs/README.md) for its scope and limits; the hosted Xcode build remains required.


## Android viewer mathematics

Choose the viewer before connecting to a computer. Uncorrected is the default; the saved selection is separate from the session-only decoder consent.
The selection is captured with the physical panel dimensions for the paired connection and cannot change during that connection.
The request and authenticated reply must agree on the complete panel descriptor, including its lens profile.
No mode changes the RDP dimensions, eye order, separation or output format.

- **Uncorrected:** read each received texel at its own panel pixel.
- **Shinecon fitted (D):** use the existing Android `headset_fitted.frag` mapping and strength 0.13, including its integer centre, diagonal-normalized radius and half-pixel operations. This is the previously checked fit for one adjustable viewer and Android phone placement, not a universal Shinecon calibration. The iPhone UI explicitly asks to check alignment through the lenses before using it. It is not composed with QR correction.
- **Cardboard QR profile:** use the actual profile parameters and pinned Cardboard inverse-distortion mesh. Cardboard v1 is an explicit choice, not a substitute for an invalid imported profile. Import accepts the full `google.com/cardboard/cfg?p=` link without network access; expand a short link separately first. The separate Cardboard profile program and its scanner are still to follow.

The geometry source, exact default profile and phone DPI table are documented in [ThirdParty/Cardboard/README.md](ThirdParty/Cardboard/README.md).
Unknown physical screen dimensions or invalid profiles produce a localized refusal; no DPI or optical coefficient is guessed.
Both corrected paths retain a full-panel viewport and sample only the selected eye's texels; QR geometry uses separate half-panel scissors and fitted samples outside the eye are black.
There is no extra resize to hide black edges or fill a lens mask.

The ordinary CMake suite tests real Cardboard v1 and I/O 2015 profiles, malformed inputs, known/unknown screen dimensions and fitted eye bounds.
On Linux with EGL, GLES2 and OpenSSL development packages, add `-DFH_VIEWER_GLES_TEST=ON -DFH_VIEWER_SESSION_TEST=ON` to the CMake command above.
The GLES test runs the actual Android shader against the same scalar mapping compiled into Metal: uncorrected output is exact, fitted output allows one channel value of interpolation rounding, and an intentionally wrong coefficient must fail the comparison.
The paired-panel test accepts the selected profile only through the pinned identity and rejects an altered profile echo.
A separate comparison with unmodified Cardboard at the pinned commit and its generated protobuf parser checked every vertex, UV and index byte for both real profiles at 1920x1080, 2340x1080 and 1280x720.
None of these Linux checks executes Metal or establishes optical alignment on an iPhone.
