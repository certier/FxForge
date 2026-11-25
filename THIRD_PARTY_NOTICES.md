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
- License: GNU General Public License, version 2
- License text: `third_party/licenses/proot-GPL-2.0`
- Build: Android NDK r28c, API 21 target, static ARM64 executable, 16 KiB ELF
  segment alignment. The binary is built in GitHub Actions from the pinned
  source and packaged as an extracted native payload. It is not loaded into
  the FxForge process with `System.loadLibrary`.

## talloc

- Component: talloc 2.4.2, statically linked into the PRoot executable
- Source: included in the pinned PRoot source repository under
  `third_party/talloc-2.4.2`
- License: GNU General Public License, version 3
- License text: `third_party/licenses/talloc-GPL-3.0`
