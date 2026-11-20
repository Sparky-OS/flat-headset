# Cardboard viewer geometry

Source: Google Cardboard v1.36.0, commit `8bd424da7f1e399255a1b6e7c02be8e281ecc974`, [upstream](https://github.com/googlevr/cardboard/tree/8bd424da7f1e399255a1b6e7c02be8e281ecc974).
`sources.json` records original paths and SHA-256 hashes; `LICENSE` and original copyright notices are retained.
The unsigned application artifact includes this Apache-2.0 license beside the other dependency licenses.
Credits: Google LLC and the Google Cardboard contributors.

The portable subset is the lens distortion, polynomial distortion, mesh and matrix implementation, public types, exact Cardboard v1 profile bytes, device-parameter schema and upstream iPhone DPI table.
No Apple SDK file, platform renderer, sensor, tracking code or prebuilt Cardboard library is included.

Local adaptations:

- `lens_distortion.h` includes our `Viewer/ProfileParams.hpp` instead of generated/platform-specific protobuf headers. Its constructor takes validated parameters and physical screen width/height.
- `lens_distortion.cc` takes those inputs instead of parsing bytes and querying platform screen dimensions. The FOV, viewport and mesh calculations below the constructor are unchanged.
- `polynomial_radial_distortion.cc` refuses a nonfinite secant result or an inverse that has not converged after 64 iterations, rather than looping without a bound. Valid profiles retain the original tolerance and arithmetic.

`Viewer/Viewer.cpp` parses the fields consumed by this subset according to `proto/cardboard_device.proto`, with bounded input, finite values and required distortion fields.
It accepts the exact default bytes or full base64url profile link and never invents coefficients.
`Viewer/PhoneDpi.inc` is generated from the iPhone rows of `qrcode/ios/sdk.bundle/resolutions.csv`; unknown models are refused instead of using the upstream generic 326-DPI fallback.
The 0.003-metre screen-border value remains the upstream geometry's constant.
The copied mesh's V coordinate is flipped for Metal as in upstream `sdk/rendering/ios/metal_distortion_renderer.mm`; vertex positions and indices are unchanged.
Each eye uses 1600 vertices and 3158 strip indices.
The native renderer additionally confines sampling and rasterization to that eye's own pixels.

The Shinecon fitted D path is separate: it follows this repository's Android `FittedLensCorrection.kt`, `headset_fitted.frag` and `FITTED-LENS-CORRECTION.md`.
Its existing 0.13 fit is not a Cardboard radial coefficient and must never be inserted into a QR profile.
Credits for that fitted convention: Richard Spindler and Daniel Oberhoff (FFmpeg lenscorrection), with the Android Flat Headset implementation in this repository.
