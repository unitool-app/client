import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `cr-note` (.ai-notes/cr-note): a secret app of notes behind a face and a PIN, where every PIN is a
 * directory of its own and `178500` wipes them all; out only encrypted, age or `openssl enc`.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

group = "cx.m42.apps"
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
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.compose.unstyled)
            implementation(libs.kotlinx.serialization.json)
            // AES-GCM and HKDF for the files, age and `openssl enc` for export and import.
            implementation(project(":unitool-kit"))
            // The chip key behind the face, and a directory of bytes in no backup: contracts here,
            // the Keystore and the Secure Enclave in the host (D654, D662).
            implementation(project(":services:vault"))
            // The phone's own SSH keys and the saved ones, to export to (D660).
            implementation(project(":services:ssh"))
            implementation(project(":services:qr-scanner"))
            implementation(project(":services:file-picker"))
            implementation(project(":services:share"))
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "cx.m42.apps.crnote"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
