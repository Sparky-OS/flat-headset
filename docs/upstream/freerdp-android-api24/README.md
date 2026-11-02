# FreeRDP on Android 7 to 9: evidence for the compatibility series

Flat Headset embeds FreeRDP's Android core (freeRDPCore) with minSdk 24, so older phones can serve as stereo headsets.
These files back the five-commit series offered to FreeRDP on master 9f9d97e81 (branch order346e-android-embedding of Sparky-OS/FreeRDP).

- freerdp-android-compatibility-evidence.zip: the reproducer harness, build commands and logs (each commit built alone, and the full series), logcat, the RDP server logs and screenshots, for API 24, 28 and 29, on master and on the series.
- SHA-256: 522633a28a4ab65974c5e397e471467c94f7d1e61145ac142e6cb490b4979dce

Master crashes during startup on API 24 and 28 and passes on API 29; the series passes on all three, with real sessions against an isolated FreeRDP shadow server.
