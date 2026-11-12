rootProject.name = "Unitool"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

/**
 * The library, as a package.
 *
 * Unitool always writes Maven coordinates (`cx.m42.superizer:core:…`), never `project(":core")`.
 * When the library's checkout is next door, `includeBuild` substitutes the local sources for those
 * coordinates and a change to the contract is visible here on the next build — Gradle's `npm link`.
 * When it is not — CI, a clone without it, or `SUPERIZER_FROM_MAVEN=1` on purpose — the same
 * coordinates resolve out of a repository instead, which is the only way to find out whether the
 * thing actually publishes.
 *
 * That is why no build file in this repository may name a library project directly: the two modes
 * have to be the same text.
 */
val superizerDir = file("../superizer")
if (superizerDir.exists() && System.getenv("SUPERIZER_FROM_MAVEN") != "1") {
    includeBuild(superizerDir)
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")

// Unitool's own apps. They live here rather than in the library because they are the product;
// the bench app lives there because it is part of the framework (12 §1).
include(":apps:calculator")
include(":apps:currency-converter")
include(":apps:twofa")
include(":apps:date-diff")
include(":apps:counters")
include(":apps:qr-scanner")
include(":apps:passwords")
include(":apps:cr-restore")
include(":apps:cr-note")

// The apps of app-learn (.ai-notes/app-learn D640–D641, D671): developed in their own repository, here
// only as the submodule `external/app-learn`, which a plain clone leaves empty. CI checks it out at the
// head of its main (D674) and every `apps/<name>` with a build file is in, as `:apps:<name>` — the same
// path it has there; without the submodule Unitool builds as it always did. The rest of that repository
// is its own host. composeApp registers them (D673).
file("external/app-learn/apps").listFiles().orEmpty()
    .filter { it.resolve("build.gradle.kts").isFile }
    .sortedBy { it.name }
    .forEach { dir ->
        check(!file("apps/${dir.name}").exists()) {
            "external/app-learn/apps/${dir.name} has the name of Unitool's own apps/${dir.name}"
        }
        include(":apps:${dir.name}")
        project(":apps:${dir.name}").projectDir = dir
    }

// Platform capabilities an app may ask for by key (D19), each with its host-side implementation.
include(":services:qr-scanner")
include(":services:share")
include(":services:vault")
include(":services:ssh")
include(":services:file-picker")
include(":services:backup")
include(":services:link")

// Code the phone and the console tool share (.ai-notes/done/cli D273): one copy, used by both.
include(":unitool-kit")

// The console tool (.ai-notes/done/cli). It lives here rather than in a repository of its own so the phone
// and the terminal are one codebase (D280): code both need moves to a shared module, never a copy.
include(":unitool-cli:core")
include(":unitool-cli:testing")
include(":unitool-cli:apps:qr")
include(":unitool-cli:apps:split")
include(":unitool-cli:apps:password")
include(":unitool-cli:apps:browser")
include(":unitool-cli:app")
