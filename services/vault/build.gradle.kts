import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Encryption at rest with a key no app can read — an optional service in the sense of D19.
 *
 * The host's vault on a phone (ssh-new 05 §3.2, D182): implementations of Superizer's
 * `SecretVault` — AES-GCM under a key in Android's Keystore, ECIES under a key in the Secure
 * Enclave. The interface moved into the library with contract 3; these stay Unitool's (D181).
 * Desktop and the web have no chip, and their vault is the person's SSH key (`AgeVault`).
 *
 * The Android side is tested on a device (`connectedAndroidTest`), because the Keystore is the
 * thing under test and nothing off a device has one.
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
        iosMain.dependencies {
            // The CoreFoundation glue, shared with the keyring's Secure Enclave backend (07 §3, question 84).
            implementation(project(":services:ssh"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
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
    namespace = "cx.m42.services.vault"
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
