plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("YtIndexerDatabase") {
            packageName.set("com.ytindexer.shared.db")
        }
    }
}

kotlin {
    // AGP 9's KMP library plugin: this `android` block replaces both the old
    // androidTarget() declaration and the top-level `android {}` block.
    android {
        namespace = "com.ytindexer.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // Runs commonTest/androidUnitTest on the JVM -- this is what CI executes.
        withHostTest {}
    }

    // Only Android ships in v1. iOS/desktop targets can be added here later
    // without touching any of the commonMain code below.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.koin.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.ext)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.security.crypto)
            implementation(libs.sqldelight.android.driver)
        }
    }
}
