# Fitted lens correction

The Flat Headset page offers Uncorrected, Shinecon fitted (D), and Viewer QR profile. Selection is saved and used for movie playback and the native-panel RDP viewer. Uncorrected remains the default. The fitted mode bypasses the Cardboard profile; the two corrections are never composed.

The D setting was selected in a physical comparison on a 2340×1080 phone in an adjustable VR Shinecon. It is specific to that phone placement, focus and lens spacing, not a general calibration for that brand. Recheck after moving the phone or changing the lenses. Feed ordinary uncorrected content; do not feed the prewarped calibration clips when the fitted mode is enabled.

The shared GLES shader operates independently on the two half-panel viewports. It reproduces the radial sampling convention used by FFmpeg lenscorrection with k1=0.13, k2=0, cx=cy=0.5 and bilinear interpolation, including its half-pixel convention. Out-of-bounds samples become black. Sampling cannot cross the eye boundary. The guide and notch masks remain physical-panel overlays after correction.

These coefficients are not Cardboard profile coefficients. A viewer QR profile also describes physical geometry. This manual fit does not replace that measurement or qualify tracking, latency or sustained comfort.

The production shader was exercised using GLES2 on llvmpipe with both texture orientations at 2340×1080 and 1280×720. Every RGB channel differed from the independent FFmpeg reference by at most one code value. Bypass was pixel-exact. Reversing the coefficient failed the reference comparison. Android build and installation checks passed. Physical viewing of the original unwarped movie and the private streamed-desktop LEFT/RIGHT chart subsequently passed with the fitted mode selected on the test Xiaomi/VR Shinecon. These are user-observed results at the current alignment and lens settings, not physical pixel captures or latency measurements.

Credits: this continues Fabian Druschke and the KDE Connect developers, Google Cardboard, AndroidX Media3, Harsha Raghu and the PhoneVR/ALVR developers. FFmpeg lenscorrection credits Richard Spindler (the original frei0r plugin) and Daniel Oberhoff. The shader is a GLSL implementation of that sampling convention.

References:
- https://ffmpeg.org/ffmpeg-filters.html#lenscorrection
- https://www.ffmpeg.org/doxygen/8.1/vf__lenscorrection_8c_source.html
- https://support.google.com/cardboard/manufacturers/answer/6324808?hl=en
