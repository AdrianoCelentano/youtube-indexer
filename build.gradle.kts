plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Static analysis is configured once here rather than repeated in every module.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.get())
        // Generated sources (BuildConfig, Compose resources, SQLDelight) are not ours to format.
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        // detekt defaults to the src/main/kotlin layout, which KMP modules do not use --
        // without this it reports NO-SOURCE and silently analyses nothing in :shared.
        // Directories that don't exist in a given module are ignored.
        source.setFrom(
            layout.projectDirectory.dir("src/commonMain/kotlin"),
            layout.projectDirectory.dir("src/commonTest/kotlin"),
            layout.projectDirectory.dir("src/androidMain/kotlin"),
            layout.projectDirectory.dir("src/androidHostTest/kotlin"),
            layout.projectDirectory.dir("src/main/kotlin"),
            layout.projectDirectory.dir("src/test/kotlin"),
        )
        // Type resolution is off: detekt 1.23.x predates Kotlin 2.4 and its
        // analysis API cannot resolve this project's types. Syntactic rules still run.
        ignoreFailures = false
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        exclude("**/build/**")
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
