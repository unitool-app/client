/**
 * The `unitool` binary: the list of apps and `main` (.ai-notes/done/cli 01 §2.2). A native Linux
 * executable (D291): no JVM, one file, libc and libz from the system.
 *
 * `./gradlew :unitool-cli:app:install` links the release build and puts it at
 * `unitool-cli/app/build/dist/unitool`. `unitool-cli/bin/unitool` does that when needed and runs it.
 *
 * `./gradlew :unitool-cli:app:installStatic` links the same program fully static, with nothing from
 * the system at all, and puts it at `build/dist-static/unitool-linux-x86_64`, with its SHA-256 next to
 * it in `unitool-linux-x86_64.sha256` (`sha256sum -c` format). That is the file CI publishes
 * (.github/workflows/cli.yml). How it is linked is in `static-ld.sh`.
 */
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries.executable {
            baseName = "unitool"
            entryPoint = "cx.m42.unitool.cli.app.main"
        }
        binaries.executable("static", listOf(NativeBuildType.RELEASE)) {
            baseName = "unitool"
            entryPoint = "cx.m42.unitool.cli.app.main"
            freeCompilerArgs += "-Xoverride-konan-properties=linker.linux_x64=" + file("static-ld.sh").absolutePath
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":unitool-cli:core"))
            implementation(project(":unitool-cli:apps:qr"))
            implementation(project(":unitool-cli:apps:split"))
            implementation(project(":unitool-cli:apps:browser"))
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

// The linker is a file of this module; a change to it has to relink.
tasks.named("linkStaticReleaseExecutableLinuxX64") { inputs.file("static-ld.sh") }

val installStatic by tasks.registering {
    group = "distribution"
    description = "Links the static binary to build/dist-static/unitool-linux-x86_64, with its SHA-256."
    val link = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>("linkStaticReleaseExecutableLinuxX64")
    val linked = link.flatMap { it.outputFile }
    val dist = layout.buildDirectory.dir("dist-static")
    dependsOn(link)
    inputs.file(linked)
    outputs.dir(dist)
    doLast {
        val name = "unitool-linux-x86_64"
        val bytes = linked.get().readBytes()
        // ELF64 program headers: a PT_INTERP (3) means a dynamic loader, so not static after all.
        fun u16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        val phoff = u32(0x20)
        val interp = (0 until u16(0x38)).any { u32(phoff + it * u16(0x36)) == 3 }
        check(!interp) { "$name is not static: it asks for a dynamic loader" }
        val dir = dist.get().asFile.apply { deleteRecursively(); mkdirs() }
        dir.resolve(name).apply { writeBytes(bytes); setExecutable(true, false) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        dir.resolve("$name.sha256").writeText("$sha  $name\n")
        logger.lifecycle("$name: ${bytes.size / 1024} KB, static, sha256 $sha")
    }
}
