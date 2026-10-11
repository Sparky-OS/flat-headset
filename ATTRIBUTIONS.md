# Attributions

Flat Headset builds on the projects below. These are project-level attributions from their own records. The names listed for KDE Connect, KRdp, and KWin are the contributors returned by full-history `git shortlog -sn` for the source paths noted here. The command counts commits, not individual lines of code.

## KDE Connect

[KDE Connect](https://invent.kde.org/network/kdeconnect-kde) provides the virtual monitor plugin that can pair a phone and pass it the stream address. The relevant source path is `plugins/virtualmonitor`.

The phone link builds on Fabian Druschke's remote desktop work for KDE Connect, open for review on 2026-10-10: [kdeconnect-kde !996](https://invent.kde.org/network/kdeconnect-kde/-/merge_requests/996) on the desktop and [kdeconnect-android !673](https://invent.kde.org/network/kdeconnect-android/-/merge_requests/673) on the phone.

The contributors recorded for that path are Alexander Lohnau, Aleix Pol, Fabian Arndt, Forest Crossman, Nicolas Fella, Albert Vaca Cintora, Kai Uwe Broulik, Volker Krause, and ivan tkachenko. The repository README states that KDE Connect is licensed under GNU GPL version 2 and version 3.

## KRdp

[KRdp](https://invent.kde.org/plasma/krdp) provides the RDP server and screencast code used to stream the virtual output. The relevant source paths are `src/PlasmaScreencastV1Session.cpp` and `src/screencasting.cpp`.

The contributors recorded for those paths are Arjen Hiemstra, David Edmundson, Aleix Pol, Jack Xu, Nick Haghiri, Akseli Lahtinen, Jie Liu, Shouvik Kar, Xaver Hugl, and reali es. KRdp's CMake project file declares BSD 2-Clause licensing.

## KWin

[KWin](https://invent.kde.org/plasma/kwin) provides the compositor and the virtual output and screencast support. The relevant source paths are `src/backends/virtual`, `src/backends/drm/drm_virtual_output.cpp`, `src/plugins/screencast`, and `src/wayland/screencast_v1.cpp`.

The contributors recorded for those paths are Vlad Zahorodnii, Xaver Hugl, Aleix Pol Gonzalez, David Edmundson, David Redondo, Jan Grulich, Columbariu S, Arjen Hiemstra, Nils Fenner, Alessandro Astone, Doğukan Korkmaztürk, Kevin Ottens, Laurent Montel, Volker Krause, Aki Sakurai, Alexander Lohnau, Alois Wohlschlager, Asahi Lina, Chaojiang Luo, Conn O'Griofa, David Faure, David Weber, Devin Lin, Gatien DA ROCHA, Janne Grunau, Jay Paul, Julius Zint, Marco Martin, Markus Ebner, Michael Wyraz, Méven Car, Oliver Beard, Stanislav Aleksandrov, Zhang Liang, fililip s., and Ömer Fadıl USTA. KWin's README states that its licence is GPL version 2 or later.

## Cardboard SDK

The phone viewer uses [Google's Cardboard SDK](https://github.com/googlevr/cardboard) for viewer and lens support. Its README credits Google LLC and directs readers to the repository's LICENSE file. That file licenses SDK files under Apache License 2.0, except files covered by its Unity Companion License terms.

## FreeRDP

The phone viewer uses [FreeRDP](https://github.com/FreeRDP/FreeRDP)'s Android client library to receive the stream. Its Android client source credits the FreeRDP contributors, Marc-Andre Moreau, Thincast Technologies, Martin Fleisz, Armin Novak and Bernhard Miklautz. FreeRDP is licensed under the Apache License 2.0.

## Universal Media Server and jUPnP

The movie proof uses [Universal Media Server](https://github.com/UniversalMediaServer/UniversalMediaServer) as one DLNA server that can send a movie to the phone. Its README describes it as a DLNA, UPnP, and HTTP/S media server, and lists ik666, mik_s, SubJunk, SurfaceS, and valib as current project members. It thanks chocolateboy, ditlew, ExSport, happy.neko, infidel, js-kyle, Nadahar, Raptor399, Redlum, renszarv, SharkHunter, skeptical, taconaut, tcox, threedguru, and tomeko for major code contributions. Its LICENSE.txt is GNU GPL version 2.

UMS uses [jUPnP](https://github.com/jupnp/jupnp). It was also studied as an Android option; the implemented phone renderer uses pupnp instead. Its records credit Kai Kreuzer, Christian Bauer and the jUPnP and Cling contributors. Its project file declares CDDL-1.0.

## Media and distribution components

KPipeWire uses FFmpeg's libavcodec for software and VA-API encoding. The phone work adds h264_nvenc with CPU YUV preparation and GPU encoding. Its configuration and fallback tests pass; NVIDIA hardware performance remains unmeasured. FFmpeg credits Fabrice Bellard, Michael Niedermayer and the FFmpeg developers; KPipeWire source credits KDE and Aleix Pol Gonzalez. VA-API and NVIDIA NVENC retain their upstream project credits.

Sparky Stereo OS's credits are in its [ATTRIBUTIONS.md](https://github.com/Sparky-OS/sparky-stereo-os/blob/main/ATTRIBUTIONS.md).

## Launcher icon

The Flat Headset icon is original vector artwork by Sparky Stereo, by Daniel Ramos and partners.
Daniel Ramos directs the work; OpenAI GPT-6 Astra, through Codex, drew it, and Anthropic's Claude Sonnet, through Claude Code, integrated it (2026-10-14).
It carries no KDE Connect badge and no Sparky logo.
The Oxygen icon theme (David Miller, David Vignoni, Johann Ollivier Lapeyre, Kenneth Wimer, Nuno Pinheiro and Riccardo Iaconelli, per Debian's copyright record) was a style reference only; no Oxygen artwork was copied.
The artwork is licensed CC-BY-SA-4.0 (Daniel's decision, 2026-10-14).

## Movie decoding and native UPnP

[AndroidX Media3](https://github.com/androidx/media), Apache-2.0, credits Google and the AndroidX Media3 contributors. It supplies local movie decoding and stereo extraction, including pull request #3439.

[pupnp](https://github.com/pupnp/pupnp), under its BSD three-clause COPYING, credits Intel Corporation, Rémi Turboult, Michel Pfeiffer and its contributors in README and THANKS. It supplies native SSDP, SOAP and GENA for the phone renderer and desktop discovery.

The [MediaServer:4 and MediaRenderer:3 specifications](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver4-and-mediarenderer3/) are credited to the UPnP Forum, Open Connectivity Foundation and Digital Living Network Alliance. Implementation proofs do not imply DLNA certification.

Checked 2026-10-11. The flat display and movie path have emulated proofs. Tracked StereoDroid work and physical-device qualification remain separate.
