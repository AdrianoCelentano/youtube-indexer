# YouTube Indexer

Kotlin Multiplatform app that indexes the signed-in user's own YouTube videos (title,
description, tags, category) into a local searchable store, so they can be found again
by a free-text prompt and/or a category filter. Ships two Android surfaces: a phone/tablet
app and an Android TV app, sharing one business-logic core.

Implementation plan and ticket board live in Notion.

## Module layout

```
shared/        Kotlin Multiplatform core -- commonMain holds everything platform-agnostic:
               models, auth/session, YouTube API client, indexing engine, search engine,
               repository. androidMain holds the Android `actual` implementations.
               Only Android is built today; iOS/desktop targets can be added to the
               `kotlin { }` block without touching commonMain.

ui-common/     Compose Multiplatform composables and design tokens shared by both app
               surfaces (spacing, and later theme/color/typography).

androidApp/    Phone & tablet application. Compose Multiplatform + Material 3.

androidTvApp/  Android TV application. Compose + androidx.tv:tv-material (10-foot UI,
               D-pad focus). Registers a LEANBACK_LAUNCHER activity and declares
               touchscreen as not required.
```

Dependency direction is strictly one-way: both app modules depend on `ui-common` and
`shared`; `ui-common` depends on `shared`; `shared` depends on neither.

## Prerequisites

- **JDK 17** — required. Note that a JDK 21 *JRE* is not sufficient; the build needs a
  full JDK with `javac`.
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0`.
- `local.properties` pointing at the SDK, e.g. `sdk.dir=/opt/android-sdk`
  (not committed — create it locally).

On this VPS:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

## Build & test

```bash
./gradlew :androidApp:assembleDebug      # phone/tablet APK
./gradlew :androidTvApp:assembleDebug    # Android TV APK
./gradlew :shared:allTests               # shared-module unit tests
./gradlew build                          # everything
```

APKs land in `<module>/build/outputs/apk/debug/`.

Install on a connected device or emulator:

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidTvApp/build/outputs/apk/debug/androidTvApp-debug.apk
```

## Toolchain

Versions are centralised in `gradle/libs.versions.toml`.

| Component | Version |
| --- | --- |
| Gradle | 9.7.0 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.11.1 |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |

Two AGP 9 behaviours shaped these build files, and are worth knowing before editing them:

- AGP 9 has **built-in Kotlin support**, so the app modules must *not* apply
  `org.jetbrains.kotlin.android` — applying it is a hard error.
- AGP 9 is **not compatible with `com.android.library` + KMP**. Multiplatform modules
  (`shared`, `ui-common`) use `com.android.kotlin.multiplatform.library` instead, and
  configure Android inside `kotlin { android { ... } }` rather than a top-level
  `android { }` block.

## Current state

This is the Phase 0 scaffolding. Both apps launch to a placeholder screen that renders a
string produced by `shared`, which is what proves the module wiring end-to-end. Auth,
the YouTube API client, indexing, and search are the Phase 1+ tickets; the libraries they
need (Ktor, SQLDelight, Koin, WorkManager) are already declared in the version catalog.
