/**
 * The `unitool` binary: the list of apps and `main` (.ai-notes/cli 01 §2.2). A native Linux
 * executable (D291): no JVM, one file, libc and libz from the system.
 *
 * `./gradlew :unitool-cli:app:install` links the release build and puts it at
 * `unitool-cli/app/build/dist/unitool`. `unitool-cli/bin/unitool` does that when needed and runs it.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries.executable {
            baseName = "unitool"
            entryPoint = "cx.m42.unitool.cli.app.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":unitool-cli:core"))
            implementation(project(":unitool-cli:apps:qr"))
        }
        commonTest.dependencies {
            implementation(project(":unitool-cli:testing"))
            implementation(kotlin("test"))
        }
    }
}

val install by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Links the release binary and copies it to build/dist/unitool."
    from(tasks.named("linkReleaseExecutableLinuxX64")) { include("unitool.kexe") }
    rename { "unitool" }
    into(layout.buildDirectory.dir("dist"))
}
