import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Encryption at rest with a key no app can read — an optional service in the sense of D19.
 *
 * `commonMain` is all an app sees: [cx.m42.services.vault.SecretVault] and its key. `androidMain`
 * is the one implementation there is, AES-GCM under a key that lives in Android's Keystore.
 * Desktop and the web register nothing: neither has a keystore a browser tab or a plain JVM can
 * use, and a key kept next to the data it locks would only look like one.
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(libs.superizer.core)
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
