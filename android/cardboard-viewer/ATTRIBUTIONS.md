# Flat Headset presentation

This integration builds on Fabian Druschke's KDE Connect Android receiver
[merge request !673](https://invent.kde.org/network/kdeconnect-android/-/merge_requests/673)
and its desktop control plane
[merge request !996](https://invent.kde.org/network/kdeconnect-kde/-/merge_requests/996).
The existing KDE Connect and FreeRDP authorship and licence notices are retained.

Lens correction comes from Google LLC's
[Cardboard SDK](https://github.com/googlevr/cardboard), revision
`5969239e7c87f4cd64c8ec170ce1e7f4eb559e37`, under Apache-2.0 for the native SDK
and Java bindings used here. Unity integration is disabled. The SDK's original
licence and third-party dependency notices are included in the app notice.

QR decoding uses [ZXing core 3.5.4](https://github.com/zxing/zxing/tree/zxing-3.5.4),
Copyright ZXing authors, Apache-2.0, as stated in its source and Maven POM.
It replaces Mobile Vision in the SDK camera pipeline and does not load Google
Play services, ML Kit, Firebase or a downloadable scanner model.
The Cardboard profile parser, storage and native distortion implementation remain
Google LLC's work. This continues Fabian Druschke's receiver integration above.
