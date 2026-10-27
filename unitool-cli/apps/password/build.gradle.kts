/**
 * `unitool password` — passwords from the device's entropy: random characters, BIP-39 words, or
 * words joined by digits and symbols.
 *
 * Only the command lives here. The generator is the kit's (`cx.m42.unitool.kit.password`), shared
 * with the phone's Passwords app.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":unitool-cli:core"))
            implementation(project(":unitool-kit"))
        }
        commonTest.dependencies {
            implementation(project(":unitool-cli:testing"))
            implementation(kotlin("test"))
        }
    }
}
