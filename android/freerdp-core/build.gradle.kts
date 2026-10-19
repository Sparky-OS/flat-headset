import java.net.URI
import java.security.MessageDigest
import org.gradle.api.file.RelativePath

/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

plugins {
    id("com.android.library")
}

val freeRdpCommit = "89d8b7a1ae3b376cf77e5f85895e003e169b4cf5"
val freeRdpArchiveSha256 = "f9a98fdb759f57ae4eda224aab3e36d0803359cb8719b4f8f3e219e438387a60"
val freeRdpArchive = layout.buildDirectory.file("downloads/freerdp-$freeRdpCommit.tar.gz")
val freeRdpExtractedSource = layout.buildDirectory.dir("upstream/freerdp-source")
val freeRdpSource = layout.buildDirectory.dir("upstream/freerdp")
val freeRdpPatches = fileTree("patches") { include("*.patch") }

fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .joinToString("") { "%02x".format(it) }

val downloadFreeRdp by tasks.registering {
    inputs.property("commit", freeRdpCommit)
    outputs.file(freeRdpArchive)
    outputs.upToDateWhen {
        freeRdpArchive.get().asFile.let { it.isFile && it.sha256() == freeRdpArchiveSha256 }
    }
    doLast {
        val destination = freeRdpArchive.get().asFile
        destination.parentFile.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.part")
        URI("https://github.com/FreeRDP/FreeRDP/archive/$freeRdpCommit.tar.gz")
            .toURL()
            .openStream()
            .use { input -> temporary.outputStream().use(input::copyTo) }
        check(temporary.sha256() == freeRdpArchiveSha256) {
            "FreeRDP source archive checksum mismatch"
        }
        check(temporary.renameTo(destination)) {
            "Could not install the verified FreeRDP source archive"
        }
    }
}

val extractFreeRdp by tasks.registering(Sync::class) {
    dependsOn(downloadFreeRdp)
    from(tarTree(resources.gzip(freeRdpArchive))) {
        eachFile {
            val stripped = relativePath.segments.drop(1)
            if (stripped.isEmpty()) {
                exclude()
            } else {
                relativePath = RelativePath(relativePath.isFile, *stripped.toTypedArray())
            }
        }
        includeEmptyDirs = false
    }
    into(freeRdpExtractedSource)
}

val prepareFreeRdp by tasks.registering {
    dependsOn(extractFreeRdp)
    inputs.property("baseCommit", freeRdpCommit)
    inputs.property("archiveSha256", freeRdpArchiveSha256)
    inputs.files(freeRdpPatches)
    outputs.file(freeRdpSource.map { it.file(".kdeconnect-patched") })
    doLast {
        // Keep the Sync task's pristine output separate. Patching a directory
        // owned by Sync makes extraction run again on the next Gradle command.
        // A changed source or patch series also invalidates CMake's external
        // dependency stamps, which refer to content inside this working tree.
        layout.projectDirectory.dir(".cxx").asFile.listFiles()?.let { delete(*it) }
        project.sync {
            from(freeRdpExtractedSource)
            into(freeRdpSource)
        }
        val patches = freeRdpPatches.files.sorted()
        val command = listOf("git", "apply", "--whitespace=nowarn", "--") +
            patches.map { it.absolutePath }
        val processBuilder = ProcessBuilder(command)
            .directory(freeRdpSource.get().asFile)
            .inheritIO()
        // The extracted tree lives below KDE Connect's Git worktree. Without
        // this ceiling, `git apply` treats it as a subdirectory of the parent
        // repository and silently skips every upstream-relative patch path.
        processBuilder.environment()["GIT_CEILING_DIRECTORIES"] = rootProject.projectDir.absolutePath
        val process = processBuilder.start()
        check(process.waitFor() == 0) {
            "Could not apply the KDE Connect FreeRDP patch series"
        }
        val sessionActivity = freeRdpSource.get().file(
            "client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java"
        ).asFile
        check(sessionActivity.readText().contains("EXTRA_CONNECTION_STATE_RECEIVER")) {
            "Git did not apply the KDE Connect FreeRDP patch series"
        }
        freeRdpSource.get().file(".kdeconnect-patched").asFile.writeText(
            "base=$freeRdpCommit\npatches=${patches.joinToString(",") { it.name }}\n"
        )
    }
}

val upstreamCore = freeRdpSource.get().asFile.resolve("client/Android/Studio/freeRDPCore")

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake")) {
        dependsOn(prepareFreeRdp)
    }
}

android {
    namespace = "com.freerdp.freerdpcore"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += providers.gradleProperty("phoneAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64").split(",")
        }

        externalNativeBuild {
            cmake {
                if (providers.gradleProperty("headsetTiming").getOrElse("false").toBoolean()) {
                    arguments += "-DCMAKE_C_FLAGS=-DHEADSET_TIMING=1"
                }
                // Keep debug symbols without slowing the per-pixel streaming path.
                if (providers.gradleProperty("headsetNativeOptimized").getOrElse("true").toBoolean()) {
                    arguments += "-DFREERDP_NATIVE_OPTIMIZED=ON"
                }
                arguments += listOf(
                    "-DCMAKE_MODULE_PATH=${file("cmake").absolutePath}",
                    // FreeRDP's MediaCodec backend is explicitly experimental.
                    // Use its stable, NEON-optimized OpenH264 backend instead; a
                    // full FFmpeg build would ship hundreds of unused codecs.
                    "-DWITH_MEDIACODEC=OFF",
                    "-DWITH_FFMPEG=OFF",
                    "-DWITH_OPENH264=ON",
                    // KRdp currently exposes no RDP audio stream. Avoid shipping an
                    // unused codec (and its problematic ARMv7 assembly path).
                    "-DWITH_OPUS=OFF",
                    // Image conversion and Azure-AD helpers are unrelated to
                    // RDPGFX/NLA and would add five more shared libraries.
                    "-DWITH_WEBP=OFF",
                    "-DWITH_JPEG=OFF",
                    "-DWITH_PNG=OFF",
                    "-DWITH_CJSON=OFF",
                    "-DWITH_OPENSSL=ON"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // This module compiles a pinned upstream source tree. Keep producing a
        // report for those findings, but do not make KDE Connect's lint result
        // depend on issues that must be fixed and updated in FreeRDP upstream.
        abortOnError = false
    }

    sourceSets {
        getByName("main") {
            // KDE Connect declares the small, non-exported Activity surface it
            // actually embeds. Do not merge FreeRDP's standalone-app manifest.
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.clear()
            java.directories.add(upstreamCore.resolve("src/main/java").path)
            res.directories.clear()
            res.directories.add(upstreamCore.resolve("src/main/res").path)
        }
    }

    externalNativeBuild {
        cmake {
            path = upstreamCore.resolve("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.10.0")

    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")

    implementation("net.zetetic:sqlcipher-android:4.16.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
}
