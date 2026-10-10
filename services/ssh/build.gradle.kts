import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * SSH keys for the host (ssh-new, 07): the formats, the cryptography, the backends that hold a key
 * in the chip or under its passphrase, and the agent — everything that implements the contracts
 * Superizer declares. Unitool's, not the library's (D181).
 *
 * `commonMain` is the formats, by hand and checked against the JDK and the real `ssh-keygen` and `age`
 * in `desktopTest` (03 §4). The primitives they stand on (hashes, ciphers, curves, RSA, the CSPRNG)
 * are in `:unitool-kit`, shared with the console tool. The platforms add only what they alone have:
 * ECDSA and the chips.
 *
 * No network, ever (I-net): a module with no way to send anything cannot send a key.
 * `scripts/verify.sh` checks that nothing here depends on Ktor.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

group = "cx.m42.services"
version = "1.0.0"

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

    sourceSets {
        commonMain.dependencies {
            // The primitives and SecretBytes live in the shared kit (.ai-notes/done/cli 02 §3.1). `api`:
            // keyring users see SecretBytes in this module's public types.
            api(project(":unitool-kit"))
            api(libs.superizer.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(kotlin("test"))
        }
        val androidInstrumentedTest by getting
        androidInstrumentedTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "cx.m42.services.ssh"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
