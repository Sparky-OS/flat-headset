#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright 2026 Daniel Campos Ramos
# Run inside a build environment with the SDK/NDK, JDK and native prerequisites.
set -euo pipefail
stage=${1:?Usage: build-lean.sh NEW-STAGE CARDBOARD PUPNP MEDIA3}
cardboard=${2:?Pinned Cardboard checkout with the bundled ZXing patch}
pupnp=${3:?Pinned pupnp checkout}
media3=${4:?Pinned edition Media3 checkout}
source_dir=$(cd "$(dirname "$0")/.." && pwd)
[[ "$stage" = /* && ! -e "$stage" ]] || { echo 'Use a new absolute staging path' >&2; exit 2; }
python3 - "$source_dir" "$stage" "$cardboard" "$pupnp" "$media3" <<'PY'
from pathlib import Path
import shutil, sys
source, stage, cardboard, pupnp, media3 = map(Path, sys.argv[1:])
for path in (source, cardboard, pupnp, media3):
    if not path.is_dir() or stage.resolve().is_relative_to(path.resolve()):
        sys.exit('Use existing source roots and a staging path outside all of them')
stage.mkdir()
ignore = shutil.ignore_patterns('.git', '.gradle', '.cxx', '.kotlin', '__pycache__', '*.pyc', 'build', 'local.properties')
for path, name in ((source, 'android'), (cardboard, 'cardboard'), (pupnp, 'pupnp'), (media3, 'media3')):
    shutil.copytree(path, stage / name, ignore=ignore)
PY
export FLAT_HEADSET_LEAN_TOOL="$stage/android/tools/project-native.py"
export FLAT_HEADSET_LEAN_EVIDENCE="$stage/lean-evidence"
if [[ -z "${LEAN_KOTLIN_CLASSPATH:-}" ]]; then
    : "${GRADLE_USER_HOME:?Set GRADLE_USER_HOME to a cache with the pinned Kotlin compiler}"
    LEAN_KOTLIN_CLASSPATH=$(python3 - "$GRADLE_USER_HOME/caches/modules-2/files-2.1" <<'PY'
import os, pathlib, sys
root = pathlib.Path(sys.argv[1])
parts = []
for group, name, version in [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'),
                             ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0'),
                             ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                             ('org.jetbrains', 'annotations', '23.0.0')]:
    jars = sorted((root / group / name / version).rglob('*.jar'))
    if len(jars) != 1:
        sys.exit(f'Populate the pinned compiler dependency first: {group}:{name}:{version}')
    parts.append(str(jars[0]))
print(os.pathsep.join(parts))
PY
)
fi
export LEAN_KOTLIN_CLASSPATH
cd "$stage/android"
gradle=(bash gradlew --no-daemon --max-workers=2 --configure-on-demand
        -Pandroid.builder.sdkDownload=false -PphoneAbis=arm64-v8a,x86_64
        -PheadsetNativeOptimized=true -PheadsetTiming=true)
python3 tools/project-native.py --root "$stage/android" --component android --evidence "$FLAT_HEADSET_LEAN_EVIDENCE"
python3 tools/project-native.py --root "$stage/cardboard" --component cardboard --evidence "$FLAT_HEADSET_LEAN_EVIDENCE"
python3 tools/project-native.py --root "$stage/pupnp" --component pupnp --evidence "$FLAT_HEADSET_LEAN_EVIDENCE"
python3 tools/project-native.py --root "$stage/media3" --component media3 --evidence "$FLAT_HEADSET_LEAN_EVIDENCE"
"${gradle[@]}" --no-configure-on-demand :freerdp-core:prepareFreeRdp
python3 tools/project-native.py --root "$stage/android/freerdp-core/build/upstream/freerdp" --component freerdp --evidence "$FLAT_HEADSET_LEAN_EVIDENCE"
# Keep the validated projection; preparing the dependency again would restore authored bytes.
# OpenSSL, OpenH264 and uriparser project after extraction/patching, per ABI.
"${gradle[@]}" -x :freerdp-core:prepareFreeRdp :testDebugUnitTest \
    --tests 'org.kde.kdeconnect.plugins.remotedesktop.headset.*' :assembleDebug
printf 'APK: %s\nEvidence: %s\n' "$stage/android/build/outputs/apk/debug/kdeconnect-android-debug.apk" "$FLAT_HEADSET_LEAN_EVIDENCE"
