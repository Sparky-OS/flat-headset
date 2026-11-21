#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
if ! command -v python3 >/dev/null; then
  echo 'Python 3 is missing; install Python 3.12 or newer on the Mac before continuing.' >&2
  exit 1
fi
exec python3 "$(cd "$(dirname "$0")" && pwd)/session.py" "$@"
