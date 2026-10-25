/**
 * `unitool browser` — the computer's side of passwords from the phone (.ai-notes/passwords-browser):
 * the native messaging host the extension talks to, pairing with a phone, the list of phones.
 *
 * The protocol, the files and the words are `commonMain`, over [cx.m42.unitool.cli.browser.BrowserSystem]:
 * POSIX sockets and stdio in `linuxX64Main` (the binary), java.net in `jvmMain` (the JVM tests run the
 * same host against a phone made of the kit). Noise, age, the key file and mDNS are the kit's (D545).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":unitool-cli:core"))
            implementation(project(":unitool-kit"))
        }
        commonTest.dependencies {
            implementation(project(":unitool-cli:testing"))
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("unitool.binary", rootProject.file("unitool-cli/app/build/dist/unitool").absolutePath)
}
