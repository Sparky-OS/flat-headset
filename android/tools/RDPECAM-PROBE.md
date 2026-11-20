# Isolated camera channel probe

`rdpecam-probe.cpp` is a standalone FreeRDP server fixture. Build it with a C++17
compiler and `pkg-config --cflags --libs freerdp-server3 freerdp3 winpr3`.
Run it inside a disposable container, with core dumps disabled, passing a test
certificate and its private key as the two arguments. It listens on container
loopback port 3389, uses TLS and deliberately has no account authentication.
Keep it in an isolated test network.

The debug APK includes `SensorChannelInstrumentation`. Supply `fingerprint`
as the test certificate's SHA-256 digest, without colons, to `am instrument`.
The emulator reaches the fixture at its virtual router's `10.0.2.2` address;
the server and emulator must share the same container network namespace.
Grant the debug application camera permission on that disposable emulator.
The certificate is pinned for this connection. No production trust option is
changed. Use an explicitly selected emulator serial for every adb command.

The client follows FreeRDP's compiled camera-capability gate. The original
camera-disabled build can establish RDP while rejecting the camera DVC.
The client instrumentation reports connection state only. The server is the
independent camera assertion: it must negotiate RDPECAM, enumerate and activate
an Android camera, select an advertised 640 by 480 NV12 mode and receive twelve
complete samples with at least two different byte hashes. A timeout, absent
mode, error response, truncated sample or unchanged stream fails the probe.

This explicitly selected raw mode establishes no H.264 encoding, colour/crop
accuracy, physical camera, hand tracking, pause/resume or pose-channel proof.
Keep the full client and server logs and exact APK/server hashes for both
the camera-disabled control and enabled candidate.
