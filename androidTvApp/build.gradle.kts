plugins {
    // AGP 9 has built-in Kotlin support, so no `kotlin.android` plugin here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ytindexer.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ytindexer.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs real resources to inflate/render the Compose tree.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui-common"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)

    // TV-specific Material. The TV app deliberately does NOT use compose.material3 --
    // androidx.tv:tv-material provides the focus-aware, 10-foot equivalents.
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
