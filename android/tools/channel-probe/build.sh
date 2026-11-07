#!/bin/bash
# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: Apache-2.0
# Run inside the Android build container. The project documents its public debug signing key.
set -euo pipefail
ulimit -c 0
probe_sdk=${1:?SDK root}
probe_output=${2:?output directory}
probe_debug_key=${3:?project public debug keystore}
probe_source=$(cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$probe_output/classes" "$probe_output/dex"
javac --release 8 -cp "$probe_sdk/platforms/android-37.0/android.jar" \
    -d "$probe_output/classes" "$probe_source/ChannelProbe.java"
"$probe_sdk/build-tools/36.0.0/d8" --lib "$probe_sdk/platforms/android-37.0/android.jar" \
    --min-api 24 --output "$probe_output/dex" "$probe_output"/classes/org/sparky/stereodroid/channelprobe/*.class
"$probe_sdk/build-tools/36.0.0/aapt2" link --manifest "$probe_source/AndroidManifest.xml" \
    -I "$probe_sdk/platforms/android-37.0/android.jar" -o "$probe_output/unsigned.apk"
python3 - "$probe_output" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unsigned.apk','a') as apk:
    apk.write(root/'dex/classes.dex','classes.dex')
PY
"$probe_sdk/build-tools/36.0.0/zipalign" -f 4 "$probe_output/unsigned.apk" "$probe_output/aligned.apk"
"$probe_sdk/build-tools/36.0.0/apksigner" sign --ks "$probe_debug_key" --ks-pass pass:android \
    --key-pass pass:android --out "$probe_output/channel-probe.apk" "$probe_output/aligned.apk"
