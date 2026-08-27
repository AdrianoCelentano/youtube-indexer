plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.ytindexer.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // ViewModels shared by both app surfaces (search, sync). Each app keeps its
            // own screens -- Material 3 vs tv-material aren't reusable -- but the state
            // machines behind them are identical and belong in one place.
            implementation(libs.androidx.lifecycle.viewmodel)
            // Search-result thumbnails. Only the compose artifact: the network engine is
            // an app-module concern, see androidApp/androidTvApp build files.
            implementation(libs.coil.compose)
        }
    }
}
