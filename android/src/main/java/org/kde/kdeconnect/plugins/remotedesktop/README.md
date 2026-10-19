<!--
SPDX-FileCopyrightText: 2026 KDE Connect Developers
SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
-->

# RDP remote desktop

The Android remote-desktop plugin consumes KDE Connect's
`kdeconnect.virtualmonitor` protocol version 2 and opens the exported Plasma
desktop in an embedded FreeRDP client. Users can select one monitor, the entire
workspace, or a new virtual monitor without switching between separate screen
and input plugins.

The complete wire protocol and lifecycle are documented in the desktop
`plugins/virtualmonitor/README`.

## Embedded client

FreeRDP is downloaded from a pinned, checksum-verified upstream revision and
wrapped by the `freerdp-core` Android library module. Its provenance, local
patches, CMake overrides, and update procedure are documented in
[`freerdp-core/README.md`](../../../../../../../../freerdp-core/README.md). The
native build targets arm64-v8a, armeabi-v7a, and x86_64. It enables:

- RDPGFX with AVC420/H.264 through OpenH264;
- progressive graphics and dynamic resolution;
- TLS through OpenSSL, with NLA available when the exporting server advertises
  support for it.

The build omits experimental MediaCodec support, FFmpeg's unused general codec
stack, audio codecs, image codecs, device redirection, clipboard redirection,
and the standalone FreeRDP application's exported components. The embedded
session activity is explicitly non-exported.

## Touch controls

Touch input follows KDE Connect's Remote Input touchpad model. One-finger
movement controls the cursor relatively and one-finger taps click. Two fingers
pan the local RDP viewport or pinch-to-zoom without sending pointer movement to
the host. Multi-finger taps and double-tap or long-press dragging follow the
Remote Input click settings. Sensitivity and acceleration are shared with
Remote Input. FreeRDP's separate full-screen touch-pointer overlay is disabled
for this mode so both input systems can never handle the same gesture.

## Trust boundary

The RDP host is always taken from the peer address of KDE Connect's
authenticated LAN socket; a packet cannot choose it. For protocol version 2,
the client requires a lowercase SHA-256 certificate fingerprint and configures
FreeRDP with automatic denial plus exact fingerprint pinning. Credentials and
session IDs are held only for the active session. Legacy protocol-v1 requests
use trust-on-first-use because they do not carry a certificate fingerprint.

KRdp 6.6 exports standard RDP logon over TLS and does not offer NLA. The desktop
therefore advertises `security: tls`; Android honors that authenticated value
while requiring the per-session SHA-256 certificate pin. A future exporter can
advertise `security: nla` without changing the protocol.

The app sends `clientConnected` only after FreeRDP reports a successful native
connection. Failure, disconnect, user cancellation, device loss, and server
timeout all converge on the protocol's stop/failure lifecycle.

Before the first session, the Plasma exporter reports `awaitingPermission` and
asks its local user to approve screen viewing together with mouse and keyboard
control. Android keeps the request pending and does not launch FreeRDP until
the approved exporter sends a pinned RDP endpoint. Denial or timeout is shown
as a normal session failure without exposing credentials or opening a socket.

## Diagnostics

The remote-desktop screen keeps a bounded in-memory event log for protocol,
endpoint, TLS-pinning, and FreeRDP lifecycle events. It never records passwords,
private keys, or complete certificate fingerprints. Users can copy the log or
explicitly send the latest entries to the currently authenticated KDE Connect
LAN peer. The desktop records these `action: diagnostic` packets in its user
journal with the paired device and session context. Logs are not transmitted
automatically.
