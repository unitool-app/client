import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * An app is an ordinary KMP library that happens to implement the contract.
 *
 * Note what is *not* here: no `superizer:host`, no `:composeApp`, no other app. The dependency rule
 * in the root build fails on any of the three, which is what keeps "write it as if it were
 * standalone" true rather than aspirational.
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(libs.superizer.core)
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.compose.unstyled)
            implementation(libs.kotlinx.serialization.json)
            // The scanner's contract only: the camera itself is the host's, and absent off Android.
            implementation(project(":services:qr-scanner"))
            // The export's share sheet, likewise: the contract here, the sheet in the host.
            implementation(project(":services:share"))
            // Encryption at rest for the accounts: the contract here, the Keystore in the host.
            implementation(project(":services:vault"))
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
    namespace = "cx.m42.apps.twofa"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
