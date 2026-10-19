# Native Cardboard presentation

Build against Google Cardboard SDK 1.35.0, revision
`5969239e7c87f4cd64c8ec170ce1e7f4eb559e37` from
https://github.com/googlevr/cardboard. Place its checkout alongside this
repository as `cardboard`, or pass `-PcardboardSourceDir=/path/to/cardboard`.

The module compiles the Apache-2.0 native SDK and its Java bindings. Unity
integration and Vulkan are disabled. The SDK's Unity-specific licence does
not apply to the disabled integration. The module's Gradle dependencies match
the SDK's records; its original licence is included in the app's notice.

The viewer consumes the existing MR !673 FreeRDP receiver's decoded bitmap.
No-lenses mode maps a native panel frame one-for-one. Cardboard mode samples
each half into the SDK's distortion meshes, using its default V1 profile until
saved viewer parameters are available. There is no pose tracking in this mode.

The service-free QR scanner uses bundled ZXing core 3.5.4 under Apache-2.0.
Apply `patches/cardboard-zxing.patch` to the pinned SDK before building:

```
git -C ../cardboard apply ../kdeconnect-android/cardboard-viewer/patches/cardboard-zxing.patch
```

It keeps Cardboard's camera preview and bounded worker queue, profile URL parser,
scoped storage and per-eye distortion meshes. Rear cameras are preferred; a
front or external camera works when no rear camera exists. No Play services,
Firebase, ML Kit or downloaded decoding model is required. A complete viewer
QR is decoded offline, including the `google.com/cardboard/cfg?p=` URL (which is
parsed locally). Existing shortened HTTPS profile URLs still need the URL
provider's network service; they are not a Play services dependency.
