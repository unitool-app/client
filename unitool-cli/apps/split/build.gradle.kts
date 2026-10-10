/**
 * `unitool split` — a secret encrypted into a container, its key split into parts that are QR
 * codes of ordinary-looking links (.ai-notes/done/split). A wizard at the terminal, commands for scripts.
 *
 * Only the terminal lives here: questions, words, screens. The format, the arithmetic and the
 * wizard's rules are the kit's (`cx.m42.unitool.kit.crypt`, `cx.m42.unitool.kit.split`), shared
 * with the phone (D373).
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
            // NativeBinaryTest scans the binary's PNG parts with it; only in tests.
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
