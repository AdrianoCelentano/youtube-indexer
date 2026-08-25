# YouTube Indexer

Kotlin Multiplatform app that indexes the most recent videos from every channel the
signed-in user is subscribed to (title, description, tags, category) into a local
searchable store, so they can be found again by a free-text prompt and/or a category or
channel filter. Ships two Android surfaces: a phone/tablet app and an Android TV app,
sharing one business-logic core.

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
- `local.properties` (not committed — create it locally):

```properties
sdk.dir=/opt/android-sdk
googleOauthClientIdAndroid=<your-id>.apps.googleusercontent.com
```

## Google OAuth setup

**This repo is public — never commit the client ID or any secret.** The build reads it
from `local.properties`; CI has none, so the app builds there but shows a
"not configured" screen instead of failing the build.

1. Enable the **YouTube Data API v3**:
   https://console.cloud.google.com/apis/library/youtube.googleapis.com
2. Consent screen (https://console.cloud.google.com/auth/overview) — add the scope
   `https://www.googleapis.com/auth/youtube.readonly`, and add your own account under
   *Test users* while the app is unverified.
3. Credentials → OAuth client ID → type **Android**, package `com.ytindexer.android`,
   plus the SHA-1 of the keystore you build with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Sign-in uses **AppAuth with PKCE as a public client** — no client secret and no backend.
The redirect URI is derived automatically from the client ID
(`com.googleusercontent.apps.<id>:/oauth2redirect`) and injected into the manifest, so a
wrong SHA-1 or package name shows up as `redirect_uri_mismatch` at runtime rather than at
build time.

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

## Quality checks

```bash
./gradlew ktlintCheck     # formatting (auto-fix with ./gradlew ktlintFormat)
./gradlew detekt          # static analysis
./gradlew :androidApp:lintDebug :androidTvApp:lintDebug   # Android Lint
./gradlew :shared:allTests                                # shared unit tests
./gradlew :shared:koverXmlReport :shared:koverHtmlReport  # coverage
```

CI (`.github/workflows/ci.yml`) runs these in three parallel jobs — static analysis,
tests + coverage, and APK build — and uploads reports and APKs as artifacts.

Two configuration details are load-bearing and easy to break:

- **detekt needs explicit source dirs.** It defaults to the `src/main/kotlin` layout,
  which KMP modules don't use. Without the `source.setFrom(...)` in the root build file
  it reports `NO-SOURCE` for `:shared` and `:ui-common` and silently analyses nothing.
- **`withHostTest {}`** must stay in `:shared`'s `kotlin { android { } }` block, or
  `commonTest` is never compiled and the test job passes vacuously.

## Emulators — not possible on this VPS

Android emulators **cannot run on this build host**, by design of the hardware:

- x86_64 system images require KVM. `systemd-detect-virt` reports this box is itself a
  KVM guest with no `vmx`/`svm` flags exposed, so nested virtualisation is off and
  `/dev/kvm` cannot exist. `emulator -accel-check` confirms:
  *"KVM requires a CPU that supports vmx or svm"*.
- ARM64 images don't help — the emulator refuses cross-architecture guests outright:
  *"Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64
  host."*

Anything requiring a rendered UI must therefore be verified on real hardware or a local
machine. A green build here is **not** evidence that a screen renders.

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidTvApp/build/outputs/apk/debug/androidTvApp-debug.apk
```

## Screenshot tests (how UI is actually verified here)

Because no emulator can run, Compose screens are rendered **on the JVM** with
Robolectric + Roborazzi and diffed against goldens committed under
`<module>/src/test/screenshots/`.

```bash
./gradlew :androidApp:verifyRoborazziDebug :androidTvApp:verifyRoborazziDebug  # CI check
./gradlew :androidApp:recordRoborazziDebug :androidTvApp:recordRoborazziDebug  # update goldens
```

Record goldens and **commit the PNGs** whenever a screen legitimately changes; CI fails
the build on any unreviewed visual diff.

The TV screen renders at 1080p landscape (`w960dp-h540dp-television-xhdpi` — 1920x1080 px
at xhdpi). Note that Android resource qualifiers must appear in a strict order or
Robolectric fails to parse them.

Rendering runs at **API 35, not 36**: Robolectric refuses SDK 36 unless the test JVM is
Java 21, and this project builds on Java 17. API 35 renders these screens identically.

Screenshot tests are a rendering check, not a device check — they will not catch
device-specific behaviour, real D-pad focus traversal, or GPU/driver issues.

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
