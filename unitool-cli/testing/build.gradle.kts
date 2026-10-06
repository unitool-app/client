/** `FakeIo` and `exec`: a scripted process for the tests of the framework and of every CLI app. */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":unitool-cli:core"))
        }
    }
}
