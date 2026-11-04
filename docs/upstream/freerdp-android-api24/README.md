# FreeRDP on Android 7 to 9: evidence for the compatibility series

Flat Headset embeds FreeRDP's Android core (freeRDPCore) with minSdk 24, so older phones can serve as stereo headsets.
These files back the five-commit series offered to FreeRDP on master 9f9d97e81 (branch order346e-android-embedding of Sparky-OS/FreeRDP).

- freerdp-android-compatibility-evidence.zip: the reproducer harness, build commands and logs (each commit built alone, and the full series), logcat, the RDP server logs and screenshots, for API 24, 28 and 29, on master and on the series.
- SHA-256: 522633a28a4ab65974c5e397e471467c94f7d1e61145ac142e6cb490b4979dce

Master crashes during startup on API 24 and 28 and passes on API 29; the series passes on all three, with real sessions against an isolated FreeRDP shadow server.

## First review round (FreeRDP #14003)

- freerdp-14003-review1-evidence.zip: the revised series' evidence after Armin Novak's and İbrahim Sevinç's review: the bookmark flags through AndroidX ParcelCompat (with the exact AndroidX 1.19.0 source and the original API 29 Parcel bytes as the control) and the file provider authority taken from the application package in HomeActivity and aFreeRDP's manifest; builds of each commit alone and of the series, and runtime logs and captures on API 24, 28 and 29, for master, the series and Flat Headset's own embedding.
- SHA-256: a2de620b6cb26099e43af965bb5c4bae5bd7e55f27f3e2c347d7e4e0eee705f8
- Replay: `python3 build-tools/check-review1.py` inside the archive exits 0.

## Merged

Armin Novak (akallabeth) merged FreeRDP #14003 on 4 November 2026 as [edcdcee5](https://github.com/FreeRDP/FreeRDP/commit/edcdcee54a8329952dd79c48679152beb71ba778), after the review round above with İbrahim Sevinç.
From that commit on, FreeRDP's own master runs inside apps built for API 24, so Flat Headset can follow upstream instead of carrying the series.
