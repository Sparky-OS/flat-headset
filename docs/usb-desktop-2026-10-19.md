# A usable stereo desktop on an older phone, 19 October 2026

An Android phone in an inexpensive passive viewer can now serve as a usable stereo desktop screen in our physical test.
Daniel reports that the desktop is usable and the mouse is responsive, and accepts this as the minimum working tier.
This is a practical step toward making stereo accessible with hardware people already own.
It needs a compatible phone, a viewer that fits it, and a PC running the stereo stack.

## The tested setup

- PC: AMD A10-9600P laptop, running Sparky Stereo OS with the candidate KRdp pacing update.
- Receiver: Xiaomi Redmi Note 7, Android 10, with the optimized Flat Headset test build.
- Viewer: VR Shinecon, using the fitted lens profile.
- Link: USB tethering, half top-and-bottom, H.264.
- Content: the desktop and a looping 854 × 480, 25 fps stereo video.

The phone's system bars now hide correctly through connection and configuration changes.
The largest measured improvement came from enabling optimization in the native FreeRDP code of the Android debug build while keeping debug symbols.
The PC also carries the previously tested frame-pacing changes.

## What changed in the measurements

The comparison below changes the phone's native build optimization with the same video and USB setup.
It is not a separate measurement of the earlier PC pacing update.
Each sample is approximately 15 seconds; loop scenes and system load can vary.

| Phone measurement | Unoptimized debug build | Optimized debug build | Optimized repeat |
| --- | ---: | ---: | ---: |
| GL frame submissions per second | 5.52 | 19.78 | 20.38 |
| Median frame-start to acknowledgement | 180.25 ms | 44.08 ms | 47.24 ms |
| Median bitmap update | 138.07 ms | 31.12 ms | 35.35 ms |

GL submissions count frames handed to the phone's renderer, not distinct video frames or frames reaching the eyes.
The timing is measured within the phone, not from mouse input on the PC to light from the screen.
The repeat still includes occasional long gaps, consistent with Daniel's report of slightly choppy video.

## What this establishes

The stereo desktop is a working, usable physical result on this setup, rather than only a planned phone output.
A low-cost viewer can give a compatible phone a second use as a 3D screen.
It does not establish support for every Android phone, smooth playback of every video, or acceptance of all resolution choices.
The half-resolution link trades image detail for lower decoding cost.

## First wireless comparison, later the same day

The same lighter video and half top-and-bottom choice were then tested over Direct Wi-Fi and the home network.
The active stream sockets confirmed each route, with the USB cable retained for diagnostics.

| Link | GL submissions per second in short samples | Observed cadence |
| --- | ---: | --- |
| USB, earlier accepted samples | 19.78 and 20.38 | Some unevenness remains |
| Direct Wi-Fi | 7.47, 7.14 and 12.58 | Gaps longer than one second in all three samples |
| Home Wi-Fi, PC on Ethernet | 12.03 and 14.55 | Longest observed gaps about 0.27 and 0.41 seconds |

Daniel reports that Direct Wi-Fi is very choppy and home Wi-Fi feels closer to USB.
The direct link used 5 GHz with a strong signal; these readings do not identify its root cause.
Its delivery and pacing need further investigation.
The first home-network sample still had the unused direct group active; it was disconnected before the second sample, while the home-network stream continued.

These are preliminary physical comparisons, not a controlled benchmark across identical frames and thermal conditions.
The video loops, scenes and device load can vary between samples.
The numbers are phone render submissions, not end-to-end latency or a guarantee for other devices.

## Remaining work

Reduce the phone's remaining bitmap conversion cost and investigate the PC's graphics load and frame pacing.
Test full-resolution links and heavier video separately.
Keep the usable USB baseline available while improving wireless operation.

Built on KDE Connect, FreeRDP, KWin, KRdp and KPipeWire, with Daniel Ramos directing and testing the work and GPT-6 Astra assisting through Codex.
The test builds are development work, not a new general release of Sparky Stereo OS.
