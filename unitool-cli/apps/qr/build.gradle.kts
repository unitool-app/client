/**
 * `unitool qr` — QR codes from text, to the terminal, PNG or SVG (.ai-notes/done/cli 01 §3).
 *
 * Only the command lives here. The encoder and the renderings are the kit's shared QR service
 * (`:unitool-kit`, `cx.m42.unitool.kit.qr`). ZXing is in `jvmTest` only, where NativeBinaryTest reads
 * the shipped binary's output with it.
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
        jvmTest.dependencies {
            implementation(libs.zxing.core)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The tests read PNGs back through ImageIO; no window is ever opened.
    systemProperty("java.awt.headless", "true")
    // NativeBinaryTest runs the shipped binary when :unitool-cli:app:install has built it.
    systemProperty("unitool.binary", rootProject.file("unitool-cli/app/build/dist/unitool").absolutePath)
}
