# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: MIT
"""Sparky Stereo OS Lean Code: the MR/HR compiler, ported for an operating system.

Origin: CGM Forge's HR/MR compiler (hr_mr_compiler), written by Daniel Campos Ramos. This package is a port
of the parts of it that explain MR and HR, and only those (Daniel, 2026-10-14: "yes - a "compiler" -
you can copy code from cgm, "port it"" and "without revealing IP - pick the parts - and only these
parts - that explain MR/HR"):
  - one authored source is the authority (here: the file as its package ships it, called HR);
  - the machine form (MR) is rendered from it with the removable commentary absent;
  - the same renderer gives the map from every MR line back to the authored line, so an error in an
    MR file is reported at its authored place (reconstruction on demand, refusing a stale MR);
  - one language handler per format, chosen by the file's real consumer.
The doctrine is Daniel's dual-code paradigm, published in Knowledge3D (docs/DUAL_CODE.md, 2025-08-28;
docs/HR_MR_STANDARD.md, 2025-08-29; docs/W3C/PM_KR_MR_HR_PARADIGM.md, 2026-03-15). The policy that
applies it to the edition is sparky-os/LEAN-CODE-POLICY.md.

Ported by Claude Opus 5.5 (Claude Code) for Daniel Campos Ramos, 2026-10-16. MIT licence (LICENSE),
shipped inside dpkg-stereo (Daniel, 2026-10-16: "Yes, ship it").
"""

VERSION = '0.1.7'
