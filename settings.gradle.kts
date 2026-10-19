pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A libmpv built on this PC (OwnTV_libmpv's buildscripts/wsl_build.sh), only while
        // the build command carries -Powntv.libmpvLocalRepo=<folder> (never a file) — core's :player-core then asks
        // for version "local". Core's settings carry the same lines.
        providers.gradleProperty("owntv.libmpvLocalRepo").orNull?.let { dir ->
            maven {
                name = "LocalLibmpv"
                url = uri(file(dir))
                content { includeVersion("tv.own.owntv", "libmpv", "local") }
            }
        }
        google()
        mavenCentral()
        // OwnTV's own Maven repository — public, no login: tv.own.owntv:core and :player-core
        // (built from https://github.com/ahXN00/OwnTV_Core) and tv.own.owntv:libmpv, the mpv engine
        // (https://github.com/ahXN00/OwnTV_libmpv). Served from OwnTV_Core's gh-pages branch.
        maven {
            name = "OwnTV"
            url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
            content { includeGroup("tv.own.owntv") }
        }
        // The fork's own core (https://github.com/saltimbanco/OwnTV_Core), published to that
        // repository's gh-pages branch the same way upstream's is. Scoped to the two artifacts and
        // the one version the fork published — it is upstream's core plus this fork's restored-
        // Xtream-auth fix, nothing else. libmpv and every other tv.own.owntv artifact stay with
        // upstream, so this adds a fallback rather than taking the upstream repository over.
        maven {
            name = "OwnTVCoreFork"
            url = uri("https://saltimbanco.github.io/OwnTV_Core/maven")
            content {
                includeVersion("tv.own.owntv", "core", "1.0.69")
                includeVersion("tv.own.owntv", "player-core", "1.0.69")
            }
        }
    }
}

// Local development: build against core's own source instead of the published artifact, so a core
// edit reaches this app with no publish step. Gradle substitutes the dependency automatically
// because OwnTV_Core publishes under the same group and artifact ids this app asks for. CI leaves
// owntv.corePath unset and resolves the pinned version instead. The SAME property serves the TV app
// and this one — set it once in ~/.gradle/gradle.properties, never here:
//   owntv.corePath=E:/MEGA/CODE/AI/OwnTV_Suite/OwnTV_Core
providers.gradleProperty("owntv.corePath").orNull?.takeIf { it.isNotBlank() }?.let { includeBuild(it) }

rootProject.name = "OwnTVMobile"
include(":app")
// Records the baseline profile against :app. Never built by CI — recording needs a real device.
include(":baselineprofile")
