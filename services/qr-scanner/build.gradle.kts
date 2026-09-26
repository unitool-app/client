import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * A camera that reads one QR code — an optional service in the sense of D19.
 *
 * `commonMain` is all an app sees: [cx.m42.services.qr.QrScanner] and its key. `androidMain` is the
 * one implementation there is — CameraX for the frames, ZXing for reading them, and a full-screen
 * activity that owns the permission prompt. Desktop and the web register nothing, so an app asks
 * `runtime.service(QrScanner.Key)`, gets null, and leaves its button out.
 *
 * Unitool's module rather than the library's: the contract fits `superizer:core` word for word and
 * belongs there once a second host wants a camera, but moving it is a library release, and one
 * host is not yet a reason to cut one.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
        androidMain.dependencies {
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
        }
        val androidUnitTest by getting
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "cx.m42.services.qr"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
