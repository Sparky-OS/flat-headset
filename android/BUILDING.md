# Build the Flat Headset development APK

This directory is the complete authored KDE Connect Android edition, including Flat Headset, the embedded FreeRDP integration, Cardboard bridge, DLNA renderer and tests. The [APK channel](../apk/README.md) documents installation and the matching PC update. Changes belong here; Lean Code acts only on a disposable build copy.

## Pinned inputs

| Input | Revision |
| --- | --- |
| Android edition baseline before release tooling/version changes | `c05a3fc3527d62455b2720e449ceaca341ffa3c2` |
| Google Cardboard | `5969239e7c87f4cd64c8ec170ce1e7f4eb559e37` plus `cardboard-viewer/patches/cardboard-zxing.patch` |
| pupnp | `79fc9f5dbd208888b220dcc0f4d0464bebbfa756` |
| Edition Media3 | `2e24c5508ec6d20c7eafd91c003dd5f654ceecef` |
| FreeRDP | `89d8b7a1ae3b376cf77e5f85895e003e169b4cf5`, verified archive plus `freerdp-core/patches/` |

The release's exact public source commit and hashes are recorded in `release.json` beside the APK. The downloadable source bundle carries the authored dependency trees and native dependency inputs as well as this directory. Retain each component's license and notices. Maven/Gradle/Android SDK build dependencies are still downloaded by their normal tooling; this is not an offline SDK distribution.

For a source checkout instead of the bundle, prepare siblings outside this directory:

```sh
git clone https://github.com/googlevr/cardboard.git cardboard
git -C cardboard checkout 5969239e7c87f4cd64c8ec170ce1e7f4eb559e37
git -C cardboard apply /absolute/path/to/flat-headset/android/cardboard-viewer/patches/cardboard-zxing.patch
git clone https://github.com/pupnp/pupnp.git pupnp
git -C pupnp checkout 79fc9f5dbd208888b220dcc0f4d0464bebbfa756
git clone https://github.com/danielcamposramos/media.git media3
git -C media3 checkout 2e24c5508ec6d20c7eafd91c003dd5f654ceecef
```

Build on Linux with JDK 17, Python 3, GCC/G++, Git, make, Perl, NASM, patch, pkg-config and the normal Android native build prerequisites. Install Android SDK platform 37, build tools requested by AGP, NDK `29.0.13113456`, and SDK CMake `4.1.2`. Set `JAVA_HOME`, `ANDROID_HOME` and writable `GRADLE_USER_HOME` to your installations. The wrapper pins Gradle 9.5.1; the project uses AGP 9.2.1 and Kotlin 2.4.0. Allow at least 16 GiB memory for the native build and sufficient scratch space; do not build inside the authored checkout.

From this directory:

```sh
export LEAN_KOTLIN_CLASSPATH="$(python3 tools/fetch-kotlin-lexer.py /absolute/path/to/lexer-cache)"
bash tools/build-lean.sh /absolute/path/to/new-stage /absolute/path/to/cardboard /absolute/path/to/pupnp /absolute/path/to/media3
```

The lexer fetcher verifies the four pinned JAR hashes in `tools/kotlin-lexer-lock.json`. This bootstraps Kotlin lexical analysis before Gradle compiles any project source. An existing cache with those exact compiler versions is also supported.

The wrapper copies the inputs, qualifies/projects C/C++, Java and Kotlin source, prepares the pinned FreeRDP patches, projects that tree, and runs headset unit tests plus `assembleDebug`. FreeRDP's OpenSSL, OpenH264 and uriparser dependencies project in ABI-specific source trees before their native builds. The build selects `arm64-v8a,x86_64`, native optimization and headset timing diagnostics. Find the APK under `new-stage/android/build/outputs/apk/debug/` and tests under `new-stage/android/build/test-results/testDebugUnitTest/`.

The included public debug keystore maintains compatibility with earlier edition test APKs. It is deliberately a test key, not a private production key. A future Play Store channel needs its own release-signing process.

## Lean Code and source recovery

`tools/lean-code/` contains the qualified engine. Java uses the JDK compiler lexer; Kotlin uses its compiler lexer. Literal content, documentation/notices, operational directives and token boundaries are checked. C/C++ projections retain `#line` mappings. The evidence directory records per-file input/output hashes, outcomes, reasons and authored copies for transformed files.

Not every file is projected. Unsupported formats, generated sources and unqualified inputs remain unchanged. In particular, FreeRDP's `settings_types_private.h` stays authored because CMake reads numeric IDs from its comments. A smaller source tree is not evidence of faster machine code; the release records coverage separately from physical latency observations.

Keep `new-stage/lean-evidence/` with the exact projected tree. To map a Java/Kotlin diagnostic back to authored source:

```sh
python3 tools/resolve-lean.py --evidence /absolute/path/to/new-stage/lean-evidence --component android --root /absolute/path/to/new-stage/android src/main/java/path/Example.kt 42
```

Use the actual relative source path and projected line. Optional `--column` is one-based Unicode code points, not UTF-16 units. The tool checks both hashes and refuses stale or missing sources. C/C++ compiler locations already remapped by `#line` are authored locations and should not be remapped as physical projected lines. This is source resolution, not reconstruction of comments from a binary.

Interrupted projections are refused. Start a new disposable stage rather than treating partially projected files as authored inputs. Preserve failed evidence until the cause is understood. The source bundle and released evidence allow a maintainer to inspect the complete original line without putting every documentation byte in the installed app.

## Scope of the build result

Unit tests cover connection policy, stereo fitting, fullscreen behavior, frame pacing decisions and related headset logic. They do not qualify physical H.264/HEVC decoding on every phone, Wi-Fi driver behavior or visual comfort. Repeat the documented USB baseline when testing release bytes and report the exact transport, picture size and media file characteristics.
