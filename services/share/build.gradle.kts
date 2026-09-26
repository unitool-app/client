import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Handing a file to the person, to send wherever they like — an optional service in the sense of
 * D19.
 *
 * `commonMain` is all an app sees: [cx.m42.services.share.FileSharer] and its key. `androidMain` is
 * the share sheet, behind a FileProvider of its own. Desktop and the web register nothing yet, so
 * an app asks `runtime.service(FileSharer.Key)`, gets null, and offers what it can without one.
 *
 * Unitool's module for the reason the QR scanner is: the contract belongs in `superizer:core` once
 * a second host wants it, and moving it is a library release.
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
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "cx.m42.services.share"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
