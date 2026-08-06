# Build Environment

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

This document defines the build environment for Warpnect.

The repository pins its build configuration rather than tracking newest toolchain releases automatically.

## Required Versions

| Component | Minimum | Repository Baseline | Notes |
| --- | --- | --- | --- |
| JDK | 17 | 17 | Required by Android Gradle Plugin 8.7.x. |
| Gradle | 8.9 | 8.12 | The committed wrapper uses Gradle 8.12. |
| Android Gradle Plugin | 8.7.3 | 8.7.3 | Declared in root `build.gradle.kts`. |
| Kotlin | 2.0.21 | 2.0.21 | Declared in root `build.gradle.kts`. |
| Android SDK Platform | 35 | 35 | Required by `compileSdk = 35`. |
| Android SDK Build Tools | 34.0.0 | 34.0.0 | Installed in CI. |
| Android NDK | 27.0.12077973 | 27.0.12077973 | Declared in `app/build.gradle.kts`. |
| CMake | 3.22.1 | 3.22.1 | Declared in `app/build.gradle.kts`. |

## Recommended Developer Setup

Use Android Studio Ladybug 2024.2.1 or newer with JDK 17 configured.

Command-line builds should use the committed Gradle wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

Do not rely on a globally installed Gradle unless diagnosing wrapper problems.

The wrapper pins the Gradle 8.12 distribution URL and SHA-256 checksum.

## Android SDK Configuration

Local SDK paths must stay outside version control.

Use one of:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `local.properties` with `sdk.dir`

Example `local.properties`:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

## Native Toolchain

The Android native target is `scl_core`.

The standalone host native smoke target lives under `native/` and verifies that public SCL headers compile as C++20.

## Compatibility Notes

Android Gradle Plugin 8.7 supports API level 35 and lists JDK 17, Gradle 8.9 minimum, SDK Build Tools 34.0.0, and NDK 27.0.12077973 as its compatibility baseline.

Android Studio Ladybug 2024.2.1 includes the IntelliJ 2024.2 platform and K2-mode improvements relevant to Kotlin development.

## Sources

- Android Gradle Plugin 8.7 compatibility: <https://developer.android.com/build/releases/agp-8-7-0-release-notes>
- Android Studio Ladybug release notes: <https://developer.android.com/studio/releases/past-releases/as-ladybug-release-notes>
