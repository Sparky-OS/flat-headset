#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Same source pins, same team, same bundle IDs; replace without uninstalling.
set -euo pipefail
exec bash "$(cd "$(dirname "$0")" && pwd)/first-session.sh" "$@"
