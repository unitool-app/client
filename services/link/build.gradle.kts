import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The phone's side of the link to a computer in the same network (.ai-notes/passwords-browser,
 * D534, D538) — an optional service in the sense of D19.
 *
 * `commonMain` is what an app sees: [cx.m42.services.link.LocalLink] (listen on the network and answer
 * mDNS while a screen asks for it) and [cx.m42.services.link.LinkResponder] (the phone's half of the
 * protocol over one accepted connection, on `unitool-kit`'s Noise). The sockets and the
 * announcement are each platform's:
 * - Android: a server socket and `NsdManager`, with a `MulticastLock` for as long as it listens (D552);
 * - iOS: POSIX sockets and `NSNetService`; Info.plist names the service and says why (D551);
 * - desktop: a server socket and a small mDNS responder of its own on the kit's codec — the JVM
 *   "phone" of the end-to-end test is the desktop build;
 * - web: nothing; the app gets null and offers no computers.
 *
 * Unitool's module for the reason the QR scanner is: one host wants it, and Superizer is not changed
 * for v1 (D534).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = "cx.m42.services"
version = "1.0.0"

kotlin {
    explicitApi()
    // Kept although jvmSharedMain is added by hand below: without it, iosMain stops being a source set.
    applyDefaultHierarchyTemplate()

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
            api(project(":unitool-kit"))
            implementation(libs.kotlinx.coroutines.core)
        }
        // The server socket is the same java.net code on Android and on the desktop.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        val desktopMain by getting
        desktopMain.dependsOn(jvmSharedMain)
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.withType<Test>().configureEach {
    // NativeHostTest runs the console tool's binary when :unitool-cli:app:install has built it.
    systemProperty("unitool.binary", rootProject.file("unitool-cli/app/build/dist/unitool").absolutePath)
}

android {
    namespace = "cx.m42.services.link"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
