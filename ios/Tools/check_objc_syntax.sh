#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
if [[ $(uname -s) != Linux ]]; then
    echo 'This syntax check uses Linux headers and authored test doubles; use Xcode on macOS.' >&2
    exit 1
fi
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
for source in Main.mm Desktop.mm Computers.mm ViewerSettings.mm; do
    "${CLANGXX:-clang++}" -x objective-c++ -std=c++17 -fsyntax-only \
        -fobjc-runtime=gnustep-2.0 -fobjc-arc -fblocks -Werror \
        -DFH_OBJC_SYNTAX_ONLY=1 -I "$root/Tests/ObjCStubs" \
        "$root/Sources/$source"
    echo "PASS: $source (syntax with authored test doubles, not SDK validation)"
done
