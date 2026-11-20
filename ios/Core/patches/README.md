# Receiver core patches

`0001-require-videotoolbox.patch` applies to FreeRDP 3.28.0, revision 5370fb26fbf034ecd11d3026b6ad639b5fff493f, whose source hash is in the adjacent `sources.json`.
It makes the iPhone H.264 decoder fail when VideoToolbox cannot initialize or returns a different frame format.
The original path logged a software fallback and continued.
The Linux decoder path is unchanged.
`build_core.sh` applies the patch before building and accepts only a clean application or that exact patch already applied.
The core archive includes this directory so its source provenance includes the local change.

FreeRDP's default branch and VideoToolbox issues/pull requests were checked on 2026-11-20.
The current default branch still permits a hardware-to-software fallback; this patch expresses the receiver's explicit failure policy on its pinned source.
The patch uses the existing failure cleanup paths and does not add a new decoder or API.
FreeRDP authors and license remain in the source and packaged FreeRDP license.
