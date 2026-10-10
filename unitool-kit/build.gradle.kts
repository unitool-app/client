import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * unitool-kit: code the phone and the console tool share (.ai-notes/done/cli D273), so neither keeps a
 * copy. Plain Kotlin in `commonMain` with no dependencies: no Superizer, no Compose, no network,
 * no coroutines (D275, checked by `checkDependencyRules`).
 *
 * Every target either side builds: the phone's (Android, desktop, iOS, web) and the CLI's native
 * `linuxX64`. Consumers write `project(":unitool-kit")`: it is part of this repository, not a published
 * package (D281).
 *
 * Today:
 * - `qr`: the encoder plus the code as a picture (PNG, SVG) and as terminal output (ANSI);
 * - `crypto`: the hand-written primitives (hashes, HMAC, HKDF, ChaCha20-Poly1305, AES, X25519,
 *   Ed25519, RSA, bcrypt), `SecretBytes` and the CSPRNG, moved from `services/ssh` (.ai-notes/done/cli
 *   02 §3.1);
 * - `split`: Shamir's secret sharing, a part's 48-byte form and its link (.ai-notes/done/split);
 * - `crypt`: the core of the `ucrypt` file format with the split recipient (.ai-notes/crypt 01).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = "cx.m42.unitool"
version = "0.1.0"

kotlin {
    explicitApi()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    linuxX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest by getting
        desktopTest.dependencies {
            // The reference: ZXing encodes the same text and must agree module for module, and it
            // reads every rendering back. Only in tests; the kit itself depends on nothing.
            implementation(libs.zxing.core)
        }
    }
}

android {
    namespace = "cx.m42.unitool.kit"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<Test>().configureEach {
    // The tests read PNGs back through ImageIO; no window is ever opened.
    systemProperty("java.awt.headless", "true")
}
