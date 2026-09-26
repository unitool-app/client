import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * SSH keys for the host (ssh-new, 07): the formats, the cryptography, the backends that hold a key
 * in the chip or under its passphrase, and the agent — everything that implements the contracts
 * Superizer declares. Unitool's, not the library's (D181).
 *
 * No network, ever (I-net): a module with no way to send anything cannot send a key.
 * `scripts/verify.sh` checks that nothing here depends on Ktor.
 *
 * Step 0 only, so far: [cx.m42.services.ssh.probe.KeystoreProbe] and the instrumented test that
 * runs it (04 §2).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
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
            api(libs.superizer.core)
        }
        val androidInstrumentedTest by getting
        androidInstrumentedTest.dependencies {
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
