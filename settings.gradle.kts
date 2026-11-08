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
    }
}

// Build against the core sources checked out next to this repo (../OwnTV_Core), so a core edit
// reaches this app with no publish step and nothing has to be copied to a scratch folder. Gradle
// substitutes the dependency automatically because OwnTV_Core publishes under the same group and
// artifact ids this app asks for. -Powntv.corePath=<dir> points somewhere else; when there is no
// sibling checkout (CI, a lone clone) this falls through to the pinned published version.
val coreDir = providers.gradleProperty("owntv.corePath").orNull?.takeIf { it.isNotBlank() }
    ?.let { java.io.File(it) }
    ?: java.io.File(settingsDir.parentFile, "OwnTV_Core")
        .takeIf { java.io.File(it, "settings.gradle.kts").isFile }
coreDir?.let { includeBuild(it) }

rootProject.name = "OwnTVMobile"
include(":app")
// Records the baseline profile against :app. Never built by CI — recording needs a real device.
include(":baselineprofile")
