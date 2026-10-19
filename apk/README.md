# Flat Headset Android development channel

This is the phone companion to the **current Sparky Stereo OS development edition**. Update the PC first, whether it is running from the development live USB or an installed system. This channel is part of distro development; the final end-user ISO remains a separate release.

## Download and install

- [APK: 19 October 2026](https://repo.sparkyos.org/apk/flat-headset/2026.10.19/flat-headset-20261019-lc-debug.apk)
- [SHA-256 checksums](https://repo.sparkyos.org/apk/flat-headset/2026.10.19/SHA256SUMS) and [signed checksums](https://repo.sparkyos.org/apk/flat-headset/2026.10.19/SHA256SUMS.asc)
- [Release/build record](https://repo.sparkyos.org/apk/flat-headset/2026.10.19/release.json)
- [Android source](../android/), [build instructions](../android/BUILDING.md), and [complete source bundle](https://repo.sparkyos.org/apk/flat-headset/2026.10.19/flat-headset-20261019-source.tar.xz)

Android 7 or later; this APK contains **arm64-v8a and x86_64** native code. It does not cover phones with a 32-bit-only Android installation.
Version `1.35.9-flatheadset.20261019.lc-debug`, version code `13510`, application ID `org.kde.kdeconnect_tp.debug`.

Download the APK on the phone, open it and allow installation from that browser or file manager when Android asks. Install over the earlier edition test build to retain its data. The official KDE Connect app has a different application ID and can coexist.

**Developer options and USB debugging are not required to install or stream.** USB tethering supplies the network connection; ADB is only an optional developer diagnostic/install tool. The USB file-transfer selector is not a substitute for enabling tethering.

This is a debug-signed development build using the existing public KDE Connect test certificate, not a Play Store release or a production signing identity. Its certificate SHA-256 is `cd993b25c8733a383fb2938026cf19f2fcd4e6e3eca6e26fe75eb1cccf25f2a3`. If Android reports an incompatible signature, check which build is already installed before removing anything; uninstalling loses its app data.

## Update the development PC first

Use an **amd64 Sparky Stereo OS development system**, with the edition's stereo compositor and dependencies already installed. This small channel updates the companion components; it is not an installer for stock Debian or a replacement for the full edition repository.

Add the signed, separate development channel:

```sh
curl --fail --location https://repo.sparkyos.org/android-test/sparky-stereo-repo.asc -o /tmp/sparky-stereo-android.asc
gpg --show-keys --with-fingerprint /tmp/sparky-stereo-android.asc
# Expected fingerprint: 10DF D38D AD1E 4808 BC68 8E5A 945E 5D07 9A2A 0B9C
sudo install -m 0644 /tmp/sparky-stereo-android.asc /usr/share/keyrings/sparky-stereo-android.asc
printf '%s\n' 'deb [arch=amd64 signed-by=/usr/share/keyrings/sparky-stereo-android.asc] https://repo.sparkyos.org/android-test mashtabba main' 'deb-src [signed-by=/usr/share/keyrings/sparky-stereo-android.asc] https://repo.sparkyos.org/android-test mashtabba main' | sudo tee /etc/apt/sources.list.d/sparky-stereo-android.list
sudo apt update
sudo apt install krdp libkpipewire6 libkpipewiredmabuf6 libkpipewirerecord6 qml6-module-org-kde-pipewire dpkg-stereo
```

Check candidates before testing:

```sh
apt-cache policy krdp libkpipewire6 libkpipewirerecord6 dpkg-stereo
```

| Component | This release |
| --- | --- |
| KRdp | `6.7.4-1+stereo3d8` |
| KPipeWire libraries/data/QML module | `6.7.4-1+stereo3d7` |
| Lean Code client | `0.1.7` |
| Maintainer instructions | `0.2.0~dev20261019` |
| Developer task packages | `20261019` |

The accepted compositor baseline is KWin `4:6.7.4-2+stereo3d28`; it is supplied by the development edition, not rebuilt in this channel. Stop existing Throw sessions and log out/in after the PC update so running processes load the new libraries. On a live USB without persistence, repeat the setup/update after reboot.

Distribution maintainers can update/install `task-sparky-stereo-os-developer` using their complete edition repositories. It includes `sparky-stereo-skills` and the ordinary developer task; the ordinary task alone does not force maintainer guidance. To install only the guidance from this channel:

```sh
sudo apt install sparky-stereo-skills
sparky-stereo-skills list
sparky-stereo-skills context native-linux --output /tmp/native-linux-context.txt
```

If an earlier local test installed a launcher override, check `type -a krdp-direct-wifi`. A copy in `~/.local/bin` can shadow `/usr/bin/krdp-direct-wifi`; retain a backup and use the packaged launcher for this release.

## Start with USB

1. Connect the phone by USB and enable **USB tethering** in Android's network settings. Keep the PC's Ethernet connected if available.
2. In Flat Headset, set **Connection → Current network / USB tethering**. Choose **Throw picture size → Half top-and-bottom: keeps horizontal detail** for the tested older-phone baseline, then press **Catch**.
3. On the PC, open Throw, choose the phone, approve the connection and select the desktop or monitor to share. Scan the viewer's QR code to set its lens profile if needed; this build uses ZXing without requiring Google Play services.
4. Stop Throw before changing picture size. Native/full-resolution choices demand more from the phone; they are not the accepted performance baseline.

If activating USB tethering changes the PC's default network route, identify the USB connection first:

```sh
nmcli -f NAME,TYPE,DEVICE connection show --active
```

Then substitute its actual connection name below (do not choose the Ethernet profile):

```sh
nmcli connection modify 'YOUR USB CONNECTION' ipv4.never-default yes ipv4.ignore-auto-dns yes ipv4.ignore-auto-routes yes ipv6.method link-local
nmcli connection up 'YOUR USB CONNECTION'
```

This keeps the phone link local while Ethernet remains the normal route. USB-profile coexistence is an explicit setup step in this release; it is not yet an automatic package migration.

## Wi-Fi options

For home Wi-Fi, put the phone on the PC's reachable local network, select **Current network / USB tethering → Catch**, and use Throw as above. The PC may stay on Ethernet. Guest-network client isolation can prevent discovery.

For **Direct Wi-Fi**, enable Wi-Fi on both devices. On Android select **Direct Wi-Fi to computer → Catch** and grant the requested nearby Wi-Fi permission (location permission on older Android). On the PC open **Throw via Direct Wi-Fi**, scan, select the phone and connect. Accept the phone's invitation, then approve Throw on the PC. The active connection helper can remain in the system tray when its window closes. Direct Wi-Fi requires compatible PC hardware/driver support.

## What is qualified

The tested older Xiaomi Redmi Note 7, VR Shinecon viewer and A10 PC reached a usable stereo desktop over USB with a responsive mouse. Video remains somewhat choppy. Home Wi-Fi felt closer to USB; Direct Wi-Fi showed longer stalls. See [the physical test report](../docs/usb-desktop-2026-10-19.md).

This release rebuilds that functional baseline with Lean Code applied before compilation, including the new Java/Kotlin adapters. Build/unit checks do not replace another physical test of these exact release bytes. The H.264 stream currently uses OpenH264 software decoding; optional HEVC follows its separate capability path. Sound stays on the PC by default.

Report problems to [Flat Headset issues](https://github.com/Sparky-OS/flat-headset/issues), with app/package versions, phone model/Android version, connection mode, picture size, source-video dimensions and whether the desktop or just video stutters. Do not include pairing secrets or private screen captures. Distinguish installation, discovery, connection, decoding and rendering failures.
