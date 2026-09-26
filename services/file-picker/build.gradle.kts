import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The platform's own "open a file" (ssh-new 04 §9, Б6) — [cx.m42.superizer.runtime.FilePicker]
 * from the library, implemented once per platform: the Storage Access Framework, a file dialog,
 * `<input type=file>`, `UIDocumentPickerViewController`.
 *
 * The contract is the library's (07 §6.1) and nothing here adds to it: the person picks, the caller
 * gets the name and the bytes, and no path or permission to come back. What the bytes are — a key,
 * a backup — is the caller's business, and so is zeroing them.
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
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "cx.m42.services.files"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
