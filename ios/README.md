# Flat Headset on iPhone

This native build proof displays a generated left/right picture through Metal, across the physical panel in landscape.
It establishes a build target for Flat Headset; it does not yet receive a desktop, pair through KDE Connect or apply a viewer profile.
The left image has a cool background and an L mark; the right has a warm background and an R mark.
Both show their complete outer edges, a grid and a circle measured in pixels, with no texture rescaling.

The unsigned workflow uses the standard hosted macOS image's installed Xcode and prints its image/compiler/SDK versions before checking out the source.
It builds for arm64 iPhone with deployment target iOS 16.0, no signing team and `CODE_SIGNING_ALLOWED=NO`.
No Apple account, certificate, secret or separately downloaded SDK is used by the workflow.
UIKit, Foundation, MetalKit and Metal are the only framework dependencies of this proof.
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
All normal operation is the generated picture; failure text is localized and visible if Metal initialization or submission fails.
The generated L/R marks identify the test eyes; they are not localized menu words.

The forthcoming receiver retains the Android design: KDE Connect pairing controls a KRdp virtual monitor, FreeRDP receives its desktop, and the actual Cardboard viewer parameters supply lens correction.
The build proof is a step toward that receiver, not a substitute for it.
