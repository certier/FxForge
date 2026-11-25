# Third-party notices

## Sora Editor

- Component: Sora Editor Android library, version 0.24.6
- Upstream: https://github.com/Rosemoe/sora-editor
- License: GNU Lesser General Public License, version 2.1 or (at your option)
  any later version
- License text: `third_party/licenses/sora-editor-LICENSE`
- FxForge consumes the published editor and Java-language artifacts without
  modifying them. FxForge's integration code and build configuration are in
  this repository; the library source is available from the upstream URL above.


## PRoot for Android ARM64

- Component: PRoot and its Android ARM64 loader binaries
- Upstream source: https://github.com/berilevi/proot-static
- Pinned source commit: `6f8ebfd8e24887dfba64c3f2d7d5fe9dc059b60e`
- Source files are licensed under GNU GPL version 2 or (at your option) any later version.
- License text: `third_party/licenses/proot-GPL-2.0`
- Build: Android NDK r28c, API 21 target, static ARM64 executable, 16 KiB ELF
  segment alignment. The binary is built in GitHub Actions from the pinned
  source commit using `scripts/build-proot-android-arm64.sh` and packaged as an
  extracted native payload. It is not loaded into the FxForge process with
  `System.loadLibrary`.

## talloc

- Component: talloc 2.4.2, statically linked into the PRoot executable
- Source: included in the pinned PRoot source repository under
  `third_party/talloc-2.4.2`
- License: GNU General Public License, version 3
- License text: `third_party/licenses/talloc-GPL-3.0`
- The combined statically linked PRoot executable is distributed under GPL
  version 3, permitted by PRoot's GPL-2-or-later terms.


## ARM64 Android Build Tools

- Component: ARM64 Linux builds of `aapt2`, `aidl`, `zipalign`, and
  `split-select` for Android Build Tools 36.0.0
- Upstream source and release: https://github.com/Commit451/android-arm-build-tools
- Release tag: `platform-tools-36.0.0`
- License: MIT
- License text: `third_party/licenses/android-arm-build-tools-MIT`
- The four downloaded executables are SHA-256 verified before use inside the
  Debian Bookworm ARM64 userland. They are not bundled in the FxForge APK.


## Debian Bookworm ARM64 root filesystem

- Base image: Debian Bookworm slim, ARM64
- Source: https://github.com/debuerreotype/docker-debian-artifacts
- Pinned source commit: `657d5664bd8e7d6833fa48225b8110774cc17666`
- Root filesystem archive SHA-256:
  `7f1aa0b5c351d0542b2066749ed47608e5873bcdd1fd14f4df6f0a05cac02f8e`
- The archive is downloaded on the device and verified before extraction.
  Debian packages retain their own copyright and license notices under the
  installed root filesystem's `/usr/share/doc` and `/usr/share/common-licenses`.


The ARM64 Android build-tools executables are derived from Android Open Source
Project components, including AAPT2, AIDL, ZIPALIGN, and split-select. Those
components are distributed under Apache License 2.0; see
`third_party/licenses/Apache-2.0`.


## Apache Commons Compress

- Component: Apache Commons Compress, version 1.28.0
- Upstream: https://commons.apache.org/proper/commons-compress/
- License: Apache License 2.0
- License text: `third_party/licenses/Apache-2.0`
- Apache-licensed transitive Apache Commons components retain their upstream
  notices and licenses in their Maven artifacts.
