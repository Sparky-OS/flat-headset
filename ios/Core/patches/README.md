# Receiver core patches

`0001-require-videotoolbox.patch` applies to FreeRDP 3.28.0, revision 5370fb26fbf034ecd11d3026b6ad639b5fff493f, whose source hash is in the adjacent `sources.json`.
Its filename is retained from the first strict-hardware integration; its policy now offers an explicit software choice after a hardware failure.
Hardware decoding remains the default for every new paired session.
No design found for remembering the software choice across sessions, so this implementation never persists it.

The patch adds a receiver-local per-decoder permission callback and passes it from GDI into both AVC420 and AVC444 surfaces before decoder initialization.
A nonblocking query lets later surfaces in the same session use already-approved software decoding.
A VideoToolbox device/format initialization failure or unexpected decoded frame format invokes the failure callback before any software frame can be presented.
The receiver clears its pending frame and hides the picture while the decoder waits.
The main thread offers Continue with software decoding or Go back, with the warning that software is slower and uses more battery, in all ten languages.
Only Continue authorizes FFmpeg software decoding within this session; Back, Stop, backgrounding and lost pairing cancel the waiter and close presentation.
Nothing silently switches, no reconnect is needed to accept the choice, and neither pairing credentials nor this choice is put in a URL or preference.

For hardware initialization failure, the accepted path opens FFmpeg without the VideoToolbox setup.
For FFmpeg hardware format negotiation failure, the accepted formats are YUV420P and YUVJ420P.
For an unexpected software frame, permission precedes taking an owned reference to its YUV420P/YUVJ420P planes; other unsupported formats still fail instead of being interpreted as those planes.
Future surfaces and codec resets retain consent only through the same session policy.
The callback also exists in the Linux build: the analogue tests invoke both failure reasons through the real core API, test each decision and cancellation, and decode the supplied H.264 eye pair using the accepted software context.
Those tests do not simulate Apple's API or establish an actual hardware decoder failure on an iPhone.

`build_core.sh` applies the patch before building and accepts only a clean application or that patch already applied.
Use a freshly extracted pinned source tree when replacing an earlier revision of this patch.
The core archive includes this directory beside sources.json, so its provenance includes the local change.
FreeRDP's default branch and VideoToolbox commits/pull requests were checked on 2026-11-20; they do not supply this receiver's per-session user choice.
FreeRDP authors and license remain in the source and packaged FreeRDP license.
