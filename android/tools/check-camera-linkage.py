#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
# SPDX-License-Identifier: Apache-2.0
"""Reject eager Camera2/ImageReader linkage in every native module of an APK.

This focused API-23 load gate does not qualify all Android API imports or
establish runtime camera support. Run with the NDK llvm-readelf in a container.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
import zipfile


def inspect(apk, readelf, scratch, system_libraries=None):
    modules = []
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(dir=scratch) as directory:
        native = [item for item in archive.infolist()
                  if len(PurePosixPath(item.filename).parts) == 3
                  and PurePosixPath(item.filename).parts[0] == "lib"
                  and item.filename.endswith(".so")]
        if not native or len(native) > 256 or sum(item.file_size for item in native) > 512 * 1024**2:
            raise ValueError("APK must contain a bounded set of native libraries")
        packaged = {}
        for item in native:
            path = PurePosixPath(item.filename)
            packaged.setdefault(path.parts[1], set()).add(path.name)
        for index, item in enumerate(native):
            path = Path(directory) / f"module-{index}.so"
            path.write_bytes(archive.read(item))
            output = subprocess.run([readelf, "--wide", "--dynamic", "--dyn-syms", str(path)],
                                    check=True, capture_output=True, text=True).stdout
            dependencies = re.findall(r"\(NEEDED\).*?\[(.*?)\]", output)
            imports = []
            for line in output.splitlines():
                undefined = re.search(r"\bUND\s+(\S+)", line)
                if not undefined:
                    continue
                symbol = undefined.group(1).split("@")[0]
                if re.match(r"^(ACamera|ACapture|AImage)(?:_|[A-Z])", symbol):
                    imports.append(symbol)
            bad_dependencies = [name for name in dependencies if name == "libcamera2ndk.so"]
            modules.append({"module": item.filename, "camera_dependencies": bad_dependencies,
                            "camera_imports": sorted(set(imports)),
                            "missing_dependencies": sorted(set(dependencies) - packaged[PurePosixPath(item.filename).parts[1]]
                                - system_libraries) if system_libraries is not None else []})
    return modules


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--readelf", required=True)
    parser.add_argument("--scratch", required=True)
    parser.add_argument("--system-libs", help="NDK sysroot ABI/API directory of platform stub libraries; also check APK dependency closure")
    args = parser.parse_args()
    system_libraries = {path.name for path in Path(args.system_libs).glob("*.so")} if args.system_libs else None
    if system_libraries is not None and not system_libraries:
        parser.error("system library directory contains no platform stubs")
    modules = inspect(args.apk, args.readelf, args.scratch, system_libraries)
    failed = any(item["camera_dependencies"] or item["camera_imports"] or item["missing_dependencies"] for item in modules)
    print(json.dumps({"apk": args.apk, "passed": not failed, "modules": modules}, indent=2))
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
