import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so no `kotlin.android` plugin here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.roborazzi)
}

/**
 * The OAuth client ID is read from `local.properties`, which is gitignored.
 *
 * This repo is public, so it must not be committed. CI has no `local.properties`, so the
 * value is empty there -- the app still builds and instead fails loudly at sign-in time
 * with a message pointing at the README.
 */
val googleOauthClientId: String =
    Properties()
        .apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }.getProperty("googleOauthClientIdAndroid")
        .orEmpty()
        .trim()

/**
 * Google's redirect URI for an "Android" OAuth client is the client ID in reverse-DNS
 * form. AppAuth registers a matching intent filter from this manifest placeholder, so a
 * mismatch here surfaces at runtime as redirect_uri_mismatch.
 */
val appAuthRedirectScheme: String =
    if (googleOauthClientId.isEmpty()) {
        // Placeholder so the manifest still merges on CI; no real redirect is possible.
        "com.ytindexer.android.unconfigured"
    } else {
        "com.googleusercontent.apps.${googleOauthClientId.removeSuffix(".apps.googleusercontent.com")}"
    }

android {
    namespace = "com.ytindexer.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ytindexer.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleOauthClientId\"")
        buildConfigField("String", "APPAUTH_REDIRECT_SCHEME", "\"$appAuthRedirectScheme\"")
        manifestPlaceholders["appAuthRedirectScheme"] = appAuthRedirectScheme
    }

    buildFeatures {
        buildConfig = true
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
    implementation(compose.material3)
    implementation(compose.ui)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.appauth)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
