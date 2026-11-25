# FxForge

FxForge is an independent Android development-workstation project. It is not
affiliated with the OpenCode team.

> **Current status:** this repository contains a native Android application
> foundation, persistent Storage Access Framework (SAF) project-folder selection
> and browser, a Kotlin + Jetpack Compose Android project scaffold generator,
> a Sora text editor for UTF-8 files up to 2 MiB, Shizuku binder/permission/
> privilege reporting, device diagnostics, bounded logcat capture, a user-
> initiated Android shell command console, and user-confirmed APK installation.
> The ARM64 APK build embeds a PRoot executable and its loaders, built from
> pinned source with the Android NDK. This is only the container engine: a
> Linux root filesystem, persistent PTY, and OpenCode are not yet installed.
> The shell console starts a fresh `/system/bin/sh` process per command through
> Shizuku. It is bounded to 60 seconds, 8 KiB of command text, and 64 KiB of
> output; it is **not** a persistent PTY. The app does **not** yet contain an
> installed Linux userland, OpenCode runtime, persistent PTY terminal, in-app
> Android SDK installer/build toolchain, Git integration, or Android MCP bridge.

## Build the Android app

Requirements:

- JDK 17
- Android SDK Platform 36 and Android SDK Build Tools 36.0.0
- Android NDK 28.2.13676358 (r28c) for the ARM64 PRoot payload
- Gradle wrapper (the project pins Gradle 8.13)

Before a local ARM64 build, stage the PRoot executable into the APK's
native-library directory. This requires Git, Python 3, and NDK r28c:

```sh
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358" \
  bash scripts/build-proot-android-arm64.sh
```

GitHub Actions performs this step automatically, runs unit tests, assembles the
debug APK, and uploads the APK and PRoot artifacts.

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

The UserService is non-daemon and explicitly unbound by FxForge. It reports
basic `Build` properties and its actual process UID, and can capture up to 500
logcat lines on explicit request. Log output is capped at 64 KiB of text,
collected through fixed `logcat` arguments (not a shell command string), and
is shown in the app only; logs may contain sensitive device data. No arbitrary
command execution or privileged filesystem access is exposed. A user-selected
APK can be installed after a separate in-app confirmation, and only when the
service reports root or ADB-shell UID. The service streams the document into a
temporary file, caps the file at 512 MiB, runs Android's package manager with
fixed arguments and a 120-second timeout, limits captured output to 8 KiB, and
removes the temporary APK afterward. Android package-manager policy and
signature checks still apply; split APKs and app launching are not implemented.

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
persisted URI permissions. The editor strictly decodes UTF-8, rejects invalid
UTF-8 and NUL-containing content, and bounds reads and writes to 2 MiB.
FxForge does not request broad shared-storage access. Shizuku permission is
user-granted through Shizuku; app status is reported as unavailable until its
binder is reachable.

## Third-party components

The project uses the unmodified Sora Editor Android library 0.24.6, licensed
under LGPL-2.1-or-later. See [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)
and the accompanying license text in `third_party/licenses/`.

## CI

The GitHub Actions workflow runs Android unit tests and assembles the debug APK
using an installed Android SDK.
