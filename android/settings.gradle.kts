import androidx.media3.buildlogic.includeMedia3

pluginManagement {
    includeBuild(providers.gradleProperty("media3SourceDir").getOrElse("../media3") + "/build-logic-settings")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}
plugins { id("gradlebuild.media3-settings-logic") }

includeMedia3(file(providers.gradleProperty("media3SourceDir").getOrElse("../media3")))

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm-util:9.6")
    }
}

rootProject.name = "kdeconnect-android"

include(":freerdp-core")
project(":freerdp-core").projectDir = file("freerdp-core")

include(":cardboard-viewer")

include(":upnp-renderer")
