# Flat Headset

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.23309028.svg)](https://doi.org/10.5281/zenodo.23309028)

Flat Headset turns a phone in a Cardboard-style viewer into a 3D screen for a Linux desktop. It shows the desktop in stereo without head tracking.
It is the phone side of Sparky Stereo OS's Throw and Catch: the PC throws its picture, and the phone catches it.

## How it works

When the phone connects, KWin, KDE Plasma's compositor as built for Sparky Stereo OS, adds a monitor in the viewer's mode and at the phone's resolution, like a 3D TV when it is plugged in. Each eye is drawn at full sharpness on its own half of the phone screen. Programs keep handing the desktop full side-by-side pictures, as they do everywhere in Sparky Stereo OS.

KRdp, KDE's RDP server, streams that monitor. H.264 is the default and HEVC is used where the phone can decode it (a third less data at the same quality, measured), with hardware encoding where available. The link can carry the phone's native picture, full side by side at 720p, half side by side or half top and bottom.

KDE Connect pairs the phone and hands it the stream, building on Fabian Druschke's remote desktop work for KDE Connect on the desktop and on Android. The phone can also be found on the local network, or reached by an address typed in.

On Android, the viewer is part of our KDE Connect build. It shows the stream with the viewer's lens correction, read from the viewer's QR code.

On a phone running Plasma Mobile, Catch is a receiver built into KRDC, KDE's remote desktop client. It fits each eye to the phone's panel and pins the PC's certificate, so the picture only comes from the PC it was approved for.

Movies need no PC at all on the way: the phone is also a DLNA renderer. Universal Media Server or another DLNA server sends it a film, the phone decodes it, and a stereo film shows split per eye while a 2D film shows as one flat screen to both eyes.

Sound stays on the PC by default and plays through its headphones or speakers.

The full VR mode with head tracking is a separate project, [StereoDroid](https://github.com/Sparky-OS/stereodroid).

## Status

Throw and Catch work, and the PC side ships in Sparky Stereo OS beta 1: KWin's phone monitor, KRdp, KPipeWire, KDE Connect, and KRDC with Catch, built for amd64, and Catch for arm64 too.
The Android viewer runs as test builds on Daniel's phone (a Xiaomi Redmi Note 9 Pro) in a VR Shinecon viewer, and comes out with the first release.
Work going on now:
- the delay, about 3 seconds on Daniel's phone on 15 October, which comes from how the phone acknowledges frames;
- reading the viewer's QR code without Google Play services;
- an option to send the sound to the phone too;
- KWin's own 3D output on a Plasma Mobile phone, beside Catch.

On 12 October the first test passed on that phone: movie mode, and a desktop test chart streamed to it, each eye on its own half with the lens correction fitted for that viewer.
On 14 October the real desktop was thrown to it, as the photos below show.

## The first test, 12 October 2026

<a href="docs/photos/phone-test-chart-left-right.jpg"><img src="docs/photos/phone-test-chart-left-right.jpg" width="99%" alt="The phone's screen showing the streamed test chart: LEFT and RIGHT halves, each with its own view of a stereo rectangle"></a>

<a href="docs/photos/through-the-lenses.jpg"><img src="docs/photos/through-the-lenses.jpg" width="49%" alt="The same chart seen through the viewer's two lenses"></a> <a href="docs/photos/phone-in-vr-shinecon.jpg"><img src="docs/photos/phone-in-vr-shinecon.jpg" width="49%" alt="The phone mounted in a VR Shinecon viewer"></a>

*The streamed chart on the phone, the same chart through the lenses, and the phone in the VR Shinecon viewer. Photos by Daniel Ramos, CC BY 4.0.*


## The desktop on the phone, 14 October 2026

<a href="docs/photos/desktop-thrown-to-phone.jpg"><img src="docs/photos/desktop-thrown-to-phone.jpg" width="99%" alt="The phone held in front of the PC monitor, showing the same desktop, the edition's GitHub page and a system monitor, as two views side by side"></a>

<a href="docs/photos/desktop-through-both-lenses.jpg"><img src="docs/photos/desktop-through-both-lenses.jpg" width="49%" alt="The desktop seen through the viewer's two lenses, photographed from between them"></a> <a href="docs/photos/desktop-through-one-lens.jpg"><img src="docs/photos/desktop-through-one-lens.jpg" width="49%" alt="The desktop seen through one lens of the viewer, up close: the GitHub page and the system monitor are readable"></a>

*The PC's desktop thrown to the phone: the phone in front of the monitor it mirrors, both lenses photographed from between them (one camera there sees the outer side of each view, while each eye sees its whole view), and one lens up close. Photos by Daniel Ramos, CC BY 4.0.*

## Sparky Stereo OS

Flat Headset is part of [Sparky Stereo OS](https://github.com/Sparky-OS/sparky-stereo-os). Its design follows the [house formats](https://github.com/Sparky-OS/sparky-stereo-os/blob/main/docs/house-formats.md) used by that project.

Sparky Stereo OS, Daniel Ramos's edition of SparkyLinux (by Paweł "pavroo" Pijanowski).

Licence: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL, the licence of KDE Connect for Android, so the code can go upstream to KDE unchanged (texts in `LICENSES/`).
