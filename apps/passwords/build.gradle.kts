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
            // The scanner's contract only: the camera itself is the host's, and absent off Android.
            implementation(project(":services:qr-scanner"))
            // The export's share sheet, likewise: the contract here, the sheet in the host.
            implementation(project(":services:share"))
            // SSH public keys and age, for the export, and the platform's secure random for the
            // generator (unitool-kit, through this module's api).
            implementation(project(":services:ssh"))
            // Listening for a paired computer in the same network, and the link's protocol
            // (.ai-notes/passwords-browser): the contract here, the sockets in the host.
            implementation(project(":services:link"))
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(kotlin("test"))
            // BrowserE2eTest's camera: it reads the QR code off a picture of the extension's popup.
            implementation(libs.zxing.core)
        }
    }
}

tasks.withType<Test>().configureEach {
    // BrowserE2eTest (passwords-browser step 7): the real binary, the built extension, its driver.
    systemProperty("unitool.binary", rootProject.file("unitool-cli/app/build/dist/unitool").absolutePath)
    systemProperty("unitool.extension", rootProject.file("unitool-browser/dist/chrome").absolutePath)
    systemProperty("unitool.driver", rootProject.file("unitool-browser/e2e/drive.mjs").absolutePath)
}

android {
    namespace = "cx.m42.apps.passwords"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
