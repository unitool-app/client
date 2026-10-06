/**
 * The command framework of the console tool (.ai-notes/cli 01 §2). An app is a word on the command
 * line with commands under it; a command declares its parameters once, and that one declaration is
 * the argv parser, the help and the menu. It knows nothing about what any command does.
 *
 * Kotlin Multiplatform with no dependencies (D291). `linuxX64` is the native binary, where the real
 * process is `SystemIo` over libc. `jvm` exists for the tests that check against JVM libraries.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()
    jvm()
    linuxX64()

    sourceSets {
        commonTest.dependencies {
            implementation(project(":unitool-cli:testing"))
            implementation(kotlin("test"))
        }
    }
}
