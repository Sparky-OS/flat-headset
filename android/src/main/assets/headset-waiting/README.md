# Throw waiting scenes

This hook uses AndroidX Media3, KDE's Flat Headset renderer and Google's Cardboard SDK.
The catalog is empty until the artwork pipeline supplies approved sports scenes.
No final artwork is included here.

`catalog.json` is a JSON array with at most 32 entries:

```json
[{"id":"basketball","connect":"basketball-throw.mp4","reconnect":"basketball-drop.mp4","still":"basketball-still.png"}]
```

Place each file beside the catalog.
Names are local basenames; URLs and directory traversal are rejected.
A new wait chooses a different scene from the last one when there is a choice.
The connecting clip shows the throw and catch; the reconnect clip drops the ball and throws it again.
Keep the camera fixed and the loop short.

Video is full side by side, left first, with a standard stereo mark recognized as Media3 `STEREO_MODE_LEFT_RIGHT`.
MP4 `st3d` or Matroska `StereoMode` are suitable containers for the existing marked-video path.
Use a hardware-decodable H.264 clip and include no audio.
The player disables audio, loops while waiting, and selects hardware decoders only.
The existing movie renderer applies the chosen lens profile.

The still is a PNG with `sTER` mode 1, left first, with identical full-size eye rectangles.
Use a total width divisible by 16 so neither eye needs PNG stereo padding.
Keep the PNG below 16 MiB and 16 megapixels.
The existing panel renderer applies the lens profile.
Reduce motion selects the still directly.
Missing media or an unavailable hardware decoder falls back to the still; missing or invalid stills leave the identical per-eye status.

Arrival of the first submitted desktop frame, pause, stop and destruction release waiting media.
The standalone receiver keeps discovery active after an unexpected disconnect and shows the reconnect wait.
Back stops discovery; it does not change the computer's outputs.

Before adding artwork, verify both connection phases, both eye images, reduce motion, decoder rejection, first-frame cleanup and pause/resume in the disposable Android rig.
The debug build contains synthetic marked fixtures for these checks in a separate catalog.
Software decoding is injected only by the debug proof activity; it never replaces the production hardware-only selection.
Unsupported video tracks can be reported without a player error; both paths must fall back to the still.
