# FxForge

FxForge is an independent Android development-workstation project. It is not
affiliated with the OpenCode team.

> **Current status:** FxForge includes a native Android app, SAF project
> browser/editor, Kotlin + Compose project scaffolder, Shizuku device diagnostics,
> bounded logcat, Android shell commands, APK installation, and an ARM64 PRoot
> executable. The app can install a checksum-pinned Debian Bookworm ARM64 root
> filesystem, Java 17, Android SDK Platform 36, ARM64 Build Tools 36.0.0, and
> Gradle 8.13. It can sync SAF projects into a private Linux workspace and build
> debug APKs. OpenCode can be installed in Debian and its authenticated web UI
> embedded in the app. An authenticated loopback MCP bridge exposes device facts
> and logcat; shell, APK installation, and app launching are separately gated
> by an explicit full-device-control toggle. The Debian Bash session persists
> across commands but uses pipes rather than a kernel PTY, so full-screen
> interactive terminal programs are not supported. Physical-device validation
> of PRoot, Debian, the SDK toolchain, OpenCode, and Shizuku MCP remains required.

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

## Android device integration

Install and start Shizuku (or Sui) separately, then grant FxForge permission
from the Device screen. FxForge reports the UID actually used by its Shizuku
UserService: root (0), ADB shell (2000), or another UID. A granted Shizuku
permission is not described as root unless the service reports UID 0.

The Device screen exposes device facts, bounded logcat capture, and a command
console. APK installation uses a user-selected document and a separate
confirmation dialog; it is limited to root or ADB-shell service UIDs, a
512 MiB APK, a 120-second timeout, and bounded output. Android package-manager
signature and policy checks still apply.

The OpenCode integration can start an authenticated loopback MCP bridge. It
exposes device facts and logcat in read-only mode. Shell commands, APK
installation, and launching installed apps are added only after the user
enables the separate full-device-control toggle. Shell commands are powerful
and can change device state; review AI-proposed commands and keep this toggle
off unless those capabilities are required. The bridge binds to loopback,
uses a random bearer token, and is stopped when OpenCode is stopped.

## Linux runtime and Android build toolchain

The ARM64 APK build embeds a statically linked PRoot executable and its
loaders. The Debian Bookworm ARM64 root filesystem is downloaded from a
pinned OCI layer and checked against its SHA-256 digest before extraction.
The Linux rootfs and all downloaded SDK packages live in FxForge's private
app storage; installation requires substantial free space and network access.

The in-app SDK setup installs Java 17, Android SDK Platform 36, Build Tools
36.0.0, and Gradle 8.13. Google's command-line SDK manager is used to install
the SDK packages after the user accepts the linked SDK terms. Android's
host-side Linux build-tools executables are not assumed to work on ARM64:
FxForge replaces the required AAPT2, AIDL, ZIPALIGN, and split-select binaries
with separately sourced ARM64 builds whose SHA-256 digests are checked.
This is an ARM64 Android application build toolchain; it does not include the
Android NDK or claim support for native C/C++ Android builds.

Projects are copied from a user-selected Storage Access Framework (SAF) tree
into a private Linux workspace before builds. FxForge syncs generated source
and APK artifacts back to the selected tree. The workspace copier excludes
build caches and local machine properties and enforces file-count, nesting,
and transfer-size limits.

The Linux shell preserves its Bash process, working directory, and environment
between commands. It uses process pipes rather than a kernel PTY, so
full-screen interactive programs, terminal multiplexers, and applications
requiring terminal ioctl support are not supported. OpenCode's local server
is launched as a Linux ARM64 process inside Debian and its authenticated web
interface is embedded in the app. This is not a general-purpose Android
desktop or virtual machine.

## Storage and editor

Project folders are selected through Android's Storage Access Framework and
persisted URI permissions; FxForge does not request broad shared-storage
access. The Sora editor strictly decodes UTF-8, rejects invalid UTF-8 and
NUL-containing content, and bounds file reads and writes to 2 MiB. Project
sync is separate from editing and build operations.

## Validation status

GitHub Actions builds the ARM64 PRoot payload, runs JVM unit tests, assembles
the Android debug APK, and publishes it as a workflow artifact. CI cannot
validate execution under Android's SELinux policy, device-specific PRoot
behavior, Shizuku privilege availability, or the embedded OpenCode/MCP
end-to-end flow. Those still require installation and testing on a physical
ARM64 Android device.
## Third-party components

The project uses the unmodified Sora Editor Android library 0.24.6, licensed
under LGPL-2.1-or-later. See [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)
and the accompanying license text in `third_party/licenses/`.

## CI

The GitHub Actions workflow runs Android unit tests and assembles the debug APK
using an installed Android SDK.


## On-device workstation setup

1. Open **Environment** and install Debian Bookworm ARM64. The root filesystem is
   downloaded from a pinned source and SHA-256 verified before extraction.
2. Install the Android SDK and ARM64 build tools. Review the linked Google SDK
   terms and explicitly accept them in the app. This installs Java 17, Android
   SDK Platform 36, Build Tools 36.0.0, and Gradle 8.13. The four native Linux
   build-tools executables are downloaded from a pinned release and verified
   against their SHA-256 digests.
3. Open **Projects**, select or create an Android Gradle project, and choose
   **Build debug APK**. FxForge copies the selected SAF folder into its private
   Linux workspace, runs Gradle, and writes `FxForge-debug.apk` back into the
   selected folder. Use the APK install action to install it through Shizuku.
4. In **OpenCode**, install the CLI, choose a project folder, and start the
   embedded server. OpenCode works in the private workspace; use **Sync** to
   copy source changes back to the SAF folder.
5. Connect Shizuku from **Device**. OpenCode's loopback MCP bridge exposes
   device facts and logcat by default. The separate full-device-control toggle
   adds shell execution, APK installation, and app launching through Shizuku.

The Debian shell preserves the current directory and environment between
commands. It is pipe-based rather than a kernel PTY, so interactive full-screen
programs such as editors and terminal multiplexers are not supported. The
Android SDK installer currently targets Java/Kotlin Gradle projects; an ARM64
Android NDK toolchain is not included.
