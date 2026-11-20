# Flat Headset on iPhone

This development target displays a generated left/right picture through Metal and provides a foreground Computers screen for KDE Connect pairing and a paired RDP session lifecycle.
Desktop reception and viewer correction are still being integrated; the generated image is a test picture.
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
The current GDI surface is not yet presented to Metal.
A connected-session status proves neither a displayed desktop nor lens correction; the generated picture remains only the earlier test.

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
The native-panel request uses physical landscape pixels, refresh rate and unknown physical dimensions (zero millimetres); `lensProfile=none` records that no profile has been selected yet.
It does not invent optics or change the transmitted eye layout.
Build the portable session adapter against the same pinned source versions with:

```sh
python3 ios/Tools/fetch_core_sources.py build/core-sources
bash ios/Tools/build_core.sh host build/core-sources build/core-host
```

Run those commands in the isolated Linux build environment with the prerequisites in the build script and four build jobs.
The next native compile must establish the app's new static-library/framework closure.
Decode presentation and viewer optics follow that compile; no device runtime result is claimed here.
