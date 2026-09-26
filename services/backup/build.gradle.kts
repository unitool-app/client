import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The host's backup, Unitool's half (ssh-new 05 §3.1, 07 §2.2): the library walks the prefixes and
 * builds the bundle; this is the cipher it hands the bundle to — age, to the keys a GitHub account
 * publishes — and the fetch of those keys. Opening a backup is the keyring's, through its sheet.
 *
 * No words here: what a person reads is in composeApp's tables.
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
            implementation(project(":services:ssh"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.superizer.testing)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "cx.m42.services.backup"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
