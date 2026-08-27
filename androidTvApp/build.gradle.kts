import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so no `kotlin.android` plugin here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.roborazzi)
}

/**
 * The TV client ID and secret are read from `local.properties`, same as the phone app's
 * client ID -- see androidApp/build.gradle.kts and the README.
 *
 * Unlike the phone app, this is Google's "TV and Limited Input devices" client type: a
 * *confidential* client that issues a secret, because PKCE is not an option for the
 * device-code grant. The secret still must not be committed -- see
 * [com.ytindexer.shared.auth.GoogleTokenRefresher]'s doc comment for why shipping it in
 * an APK is accepted as unavoidable rather than actually secret.
 */
private val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

private val googleOauthClientIdTv: String = localProperties.getProperty("googleOauthClientIdTv").orEmpty().trim()
private val googleOauthClientSecretTv: String =
    localProperties.getProperty("googleOauthClientSecretTv").orEmpty().trim()

android {
    namespace = "com.ytindexer.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ytindexer.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID_TV", "\"$googleOauthClientIdTv\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_SECRET_TV", "\"$googleOauthClientSecretTv\"")
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
    implementation(compose.ui)

    // TV-specific Material. The TV app deliberately does NOT use compose.material3 --
    // androidx.tv:tv-material provides the focus-aware, 10-foot equivalents.
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Network engine for :ui-common's VideoThumbnail. OkHttp rather than Coil's own Ktor
    // engine since :shared already depends on it.
    implementation(libs.coil.network.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // For TvSignInViewModelTest, which drives a real GoogleDeviceCodeClient against a
    // mocked HttpClient the same way :shared's own auth tests do.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
