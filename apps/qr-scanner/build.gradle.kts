import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `qr-scanner` (.ai-notes/qr-scanner): a public QR scanner — links, Wi-Fi, contacts and the rest
 * read out, anything not text as hex, and a file cut into many codes put back.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
            // The chunk format and base45 are the kit's, shared with `unitool qr encode --file`.
            implementation(project(":unitool-kit"))
            // The camera's contract only; the camera itself is the host's.
            implementation(project(":services:qr-scanner"))
            // "Save as", likewise.
            implementation(project(":services:file-picker"))
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
    namespace = "cx.m42.apps.qrscanner"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
