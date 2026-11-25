# FxForge

FxForge is an independent Android development-workstation project. It is not
affiliated with the OpenCode team.

> **Current status:** this repository contains the Android application
> foundation, persistent Storage Access Framework (SAF) project-folder
> selection, live Shizuku binder/permission/privilege reporting, and device
> diagnostics. Shizuku UserService can also return basic device facts and the
> actual service-process UID after explicit permission. It does **not** yet
> contain a Linux userland, OpenCode runtime, PTY terminal, in-app Android SDK
> installer/build toolchain, editor, or Android MCP bridge. No screen reports
> those capabilities as operational.

## Build the Android app

Requirements:

- JDK 17
- Android SDK Platform 36 and Android SDK Build Tools 36.0.0
- Gradle wrapper (the project pins Gradle 8.13)

Open the repository in Android Studio or build from a shell after installing
the SDK:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK installation. The app
targets API 36 and currently supports Android API 26 and later. The Kotlin app
uses Android's managed runtime; this does not imply that Linux ARM64 binaries
or glibc programs can run on Android.

## Shizuku

Install and start Shizuku (or Sui) separately, then grant FxForge permission
from the Device screen. Shizuku started through wireless debugging normally
runs with the Android shell UID (2000), not root (UID 0). FxForge displays the
UID actually reported by Shizuku and does not infer root access from a granted
permission. On non-root setups, Shizuku may need to be started again after a
device reboot.

The UserService is non-daemon, explicitly unbound by FxForge, and only returns
basic `Build` properties and its actual process UID. This is not arbitrary
command execution; package installation, shell, logcat, and privileged
filesystem operations are not implemented.

## Compatibility research and constraints

- The Android application uses Kotlin, Jetpack Compose, Android Gradle Plugin
  8.13.2, and Android SDK API 36.
- The Shizuku API is consumed from its published Maven artifact (13.1.5); the
  upstream API documentation describes binder lifecycle, permission requests,
  UID reporting, and the difference between shell and root backends.
- OpenCode's official documentation describes local MCP servers as processes
  launched over stdio. Its Linux release binaries are not assumed to be
  Android-compatible: ABI alone does not establish Android syscall, libc,
  terminal, or filesystem compatibility. No OpenCode binary is bundled or
  executed by this repository yet.
- No ARM64 AAPT2/AIDL distribution has been selected or verified. Google's
  host build tools are not treated as ARM64-compatible by assumption.
- No Android device is attached to this development environment. The APK
  builds locally, but physical Shizuku validation remains unverified.

## Security and data

Project folders are selected using Android's Storage Access Framework and
persisted URI permissions. FxForge does not request broad shared-storage
access. Shizuku permission is user-granted through Shizuku; app status is
reported as unavailable until its binder is reachable.

## CI

The GitHub Actions workflow runs Android unit tests and assembles the debug APK
using an installed Android SDK.
