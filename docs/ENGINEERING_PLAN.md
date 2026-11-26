# FxForge Engineering Plan

**Branch:** `codex/fxforge-full-workstation`  
**PR:** [#1](https://github.com/certier/FxForge/pull/1)  
**Status:** active engineering plan; milestones are not considered complete until their acceptance checks pass.

## Product contract

FxForge is a workstation, not a collection of unrelated tools. A developer must be able to install it, understand its requirements, open and preserve a project, edit it, run commands, build it, inspect the result, and recover from failure without silently losing work.

The existing **Workspace feature and its user data/workflow are protected**. Improvements must preserve the current project browser/editor/OpenCode integration and migrate existing private workspace data without discarding it.

No feature is marked ready just because it compiles. CI, automated tests, runtime smoke checks, and physical-device validation are reported separately.

## Repository and product baseline

The current implementation contains:

- Compose / Material 3 Android shell, bottom navigation and a width-based navigation rail.
- SAF project browser, Sora text editor, project scaffolder and APK installer.
- Debian Bookworm ARM64 rootfs running through statically bundled PRoot.
- Persistent Bash pipe session, one-shot Linux command runner, Java 17, Android SDK 36, ARM64 Build Tools and Gradle 8.13.
- OpenCode CLI installer, local authenticated web server embedded in a WebView, and a loopback Android MCP server.
- Shizuku binder/permission/UserService separation, device facts, logcat, shell and APK operations.
- JVM unit tests for a small set of helpers.

The baseline CI run before the Workspace changes passed PRoot build, unit tests, APK assembly and artifact upload: [run 45813761400](https://github.com/certier/FxForge/actions/runs/45813761400). That is CI evidence only; it does not prove that Debian, APT, OpenCode or Shizuku work on a real Android installation.

## Research: ACSIDE and related terminal work

ACSIDE is a useful product benchmark, but its README states that the main IDE is currently closed source. Its public repository does not contain the main app UI or editor implementation, so FxForge should learn from its documented workflows and release history rather than pretend it can copy or inspect the closed implementation.

The public ACSIDE material highlights several concrete practices:

- A real IDE workflow includes project templates, SDK/tool manager, dependency status, Gradle projects, a file tree, tabs, Quick Open, code folding, editor status, diagnostics, language servers and previews.
- Its alpha.7 release describes breadcrumbs, multi-select file operations, unsaved-change handling, external file change handling, Quick Open, task presets, Git state and language-server diagnostics.
- Its changelog reports fixes for focus/IME loss after rotation, editor and context-menu crashes, file-tree accessibility labels, memory leaks, project-switch latency and setup/build processes killed when switching away.
- The alpha.6 release notes say ACSIDE moved its default terminal environment from PRoot to Termux Packages for better native Bionic performance, while keeping PRoot optional through PRoot-Distro.
- Setup UX includes independent component installs, visible installed/missing state, copyable installer diagnostics, missing-dependency explanations that navigate to the relevant SDK setting, and progress that remains visible while work runs.
- Git is treated as a workflow: remote setup, credentials, diffs and merge-conflict decisions. AI edits have a review/approval step.

Sources:
- https://github.com/AndroidCSIDE/ACSIDE
- https://github.com/AndroidCSIDE/ACSIDE/releases
- https://github.com/AndroidCSIDE/ACSIDE/issues/160
- https://github.com/AndroidCSIDE/ACSIDE/issues/172
- https://github.com/AndroidCSIDE/ACSIDE/issues/89

ACSIDE's repository includes an Android Template Creator subtree and public plugin/model repositories, but those sources have separate and mixed licensing declarations (GPL and LGPL references). FxForge will not copy code from them without a file-by-file license and compatibility review.

Terminal candidates assessed:
- Termux's `terminal-view` and `terminal-emulator` are mature Android terminal libraries. Their repository documents an Apache-2.0 exception for these components, separate from the Termux app's GPLv3 license. The Termux terminal session can spawn a child process under a real PTY and report terminal resizing.
  - https://github.com/termux/termux-app
  - https://github.com/termux/termux-app/wiki/Termux-Libraries
- Google's `vtfx` is a recent Apache-2.0 Compose terminal emulator with strong IME, accessibility, resize, scrollback and security-boundary design. It does not spawn a host process or provide a PTY, requires minSdk 29, has an unstable API and is not yet published to Maven Central. It is a research candidate, not a drop-in solution for the current minSdk 26 / JDK 17 project.
  - https://github.com/google/vtfx

**Runtime decision:** retain Debian PRoot for OpenCode and the glibc-based Android build toolchain. Do not route all operations through PRoot unnecessarily. Prototype a PTY-backed terminal session that launches the existing PRoot guest, with the current one-shot command runner kept separate for automation and build tasks. A Bionic/Termux-style native package environment may be evaluated later as an optional environment, not silently substituted for Debian.

## Priority order and work packages

### P0 — Workspace integrity and single-project identity

**Goal:** the Projects screen, editor, Linux terminal, build process and OpenCode server must agree about which project is active.

Work:
1. Use one persisted project identity and one Workspace registry; retain the existing SAF tree permission and current project selection during migration.
2. Give each linked project its own private working directory and sync baseline. Do not overwrite project A when the user opens project B.
3. Implement three-way sync against a last-common baseline. Detect independent edits, additions and deletions. Do not resolve a conflict by guessing; show the exact paths and ask whether to keep the FxForge copy or the external folder copy.
4. Stage file replacements and artifact copies; verify hashes and lengths. A failed write must not truncate the previous file.
5. Make import, sync, build, project switching and process shutdown coordinate through a single workspace operation lock. Refuse or safely stop an active process before changing the directory it has mounted.
6. Add backup/export and restore for the private Workspace and its manifest. Keep recovery copies until the new data is verified.
7. Propagate sync failures and conflict state to Projects, Workspace and OpenCode screens consistently.

Acceptance:
- Import a project, edit it in FxForge and sync; edit it externally and sync back.
- Create, rename and delete files on either side; reconcile without leaving stale files or deleting an unmodified counterpart.
- Edit the same file on both sides; verify neither version is silently lost and the user can explicitly choose.
- Switch A → B → A and verify both private workspaces and histories remain intact.
- Build a non-active project while OpenCode is open on another project; verify the active Workspace is untouched.
- Inject interrupted/failed SAF reads and writes; verify recoverable original files remain.
- Reinstall/update or recreate the Activity and verify the selected project and Workspace data restore.

### P1 — Runtime and installation reliability

**Goal:** installation states reflect executable health, not just marker files.

Work:
1. Treat rootfs, Java, SDK, Gradle, developer tools, OpenCode and Shizuku as independent components with explicit prerequisites and health probes.
2. Use staged installation, checksums, version/architecture validation and rollback where replacement is destructive.
3. Preserve complete command output, exit status and the first actionable failure. Provide a copyable diagnostic report containing component, command, exit code, relevant output and environment versions.
4. Make retries idempotent. A partial SDK or OpenCode install must be repairable without reinstalling the entire Debian environment.
5. Keep long-running tasks alive across navigation and configuration changes; evaluate a foreground service for installs/builds that must survive process/background constraints. Add safe cancellation, timeouts and restart/recovery state.
6. Verify OpenCode install, CLI version, server bind address, authentication and MCP registration independently.
7. Make tool inventory query actual executable versions and installed SDK packages, not marker text alone.

Acceptance:
- Fresh install and each installer component can complete, fail and retry independently.
- A simulated network interruption leaves a repairable state.
- A bad marker or missing executable is detected and corrected.
- APT failure shows the first meaningful error, not only the final summary.
- OpenCode CLI version and local authenticated server are both verified before the UI claims ready.
- App navigation does not cancel installs; app process death does not leave false success or corrupt staged files.

### P1 — Real terminal

**Goal:** a Linux terminal that behaves like a terminal, not a text box that runs newline-delimited commands.

Work:
1. Prototype Termux `terminal-view` / `terminal-emulator` with the current PRoot executable as the child command, including environment, rootfs binds and terminal size.
2. Keep the session model in an application-scoped owner/ViewModel, not in a transient composable.
3. Implement resize, keyboard/IME input, hardware keyboard shortcuts, extra keys, selection/copy/paste, scrollback, font scale and session lifecycle.
4. Keep the one-shot command runner separate for installers, inventory and Gradle builds.
5. Explicitly show the current backend and its limitations; do not label the pipe shell as a full PTY.

Acceptance:
- Interactive `bash`, `top`, `less`, `vim` (where installed), password prompts and Ctrl-C.
- Terminal resize updates the guest PTY and programs reflow.
- Keyboard/IME, physical keyboards, copy/paste, scrollback and session reconnection work.
- Repeated open/close and rotation do not leak processes, descriptors or Activities.

### P2 — Guided setup and project/toolchain experience

**Goal:** a first-time user understands what is required, what is optional, why it is needed and what to do after each step.

Work:
1. Add first-run intro and readiness checklist: storage/network requirements, Debian, OpenCode, Android SDK/build tools, Workspace selection, and optional Shizuku.
2. Do not gate OpenCode behind Android SDK installation; the SDK is required for Android builds, not for the OpenCode CLI itself.
3. Make each SDK component inspectable and independently repairable. Distinguish Java, Gradle, SDK Platform, Build Tools, NDK and CMake.
4. Add project templates with explicit language/type, application ID, min/target SDK and build plugin/version choices; preserve the simple current Kotlin/Compose starter as a quick path.
5. Improve the project browser with breadcrumb navigation, recent projects, Quick Open, multi-select, file operations and clear binary/large-file handling.

Acceptance:
- A fresh user can reach a working OpenCode session without installing the Android SDK first.
- A user can skip Shizuku and still use Projects, Debian, OpenCode and builds.
- Missing dependencies link directly to the relevant setup action.
- Project creation output builds successfully with the selected toolchain.

### P2 — Editor, build and source-control workflow

**Goal:** move from basic text editing to a practical mobile IDE without destabilizing the existing editor.

Work:
1. Preserve Sora; add tab/session state, unsaved-change prompts, cursor/scroll restoration, external-modification detection, search/replace, Quick Open and safe multi-file operations.
2. Add a language-server layer behind an interface. Start with Kotlin/Java/XML diagnostics and navigation after memory/latency profiling; allow individual servers to be enabled or disabled.
3. Add build variants/tasks, pinned task presets, live build status, build output filtering and artifact history.
4. Add Git status, diff, clone/remote onboarding and conflict resolution only after Workspace path/credential semantics are stable.
5. Keep OpenCode/MCP changes reviewable; device-changing Android tools stay behind explicit authorization.

Acceptance:
- No lost edits after switching tabs, navigating away, rotating or returning from OpenCode.
- External edits prompt reload/keep and preserve the undo history where possible.
- Diagnostics do not block typing; large projects are profiled for latency and memory.
- Gradle build output and failed tasks link to actionable errors.
- Git and AI changes show a reviewable diff before they replace user content.

### P3 — Material 3 UI system, accessibility and performance

**Goal:** one coherent, fast workstation UI rather than a collection of visually inconsistent screens.

Work:
1. Split the large Activity into feature-oriented screens, state holders and reusable UI components.
2. Use Material 3 design tokens, responsive window-size behavior, correct system insets and consistent iconography.
3. Keep Workspace a first-class navigation destination. Use bottom navigation on compact windows, a rail or adaptive layout on expanded windows, and a project drawer that behaves consistently across rotation and multi-window.
4. Define loading, empty, offline, permission-denied, installing, failed, cancelled and recovery states for every workflow.
5. Use motion only for state change feedback; keep typing, file scrolling, terminal output and build logs unanimated and responsive.
6. Verify TalkBack labels, touch target size, contrast, font scaling, keyboard focus, RTL and reduced-animation settings.

Acceptance:
- Screenshot tests for light/dark and compact/expanded layouts.
- UI tests for navigation, first-run setup, project import and conflict dialogs.
- No clipped controls, hidden errors or ambiguous loading state at supported screen sizes.
- Profile editor typing, project list scrolling, terminal output and animation overdraw. Fix measured bottlenecks rather than guessing.
- No avoidable memory leaks across repeated project open/close or Activity recreation.

## Cross-cutting test and release gates

Every change must include the narrowest relevant automated test and pass:
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- PRoot ARM64 build and ELF/TLS checks in CI.

Before calling a runtime feature complete, test on an actual Android installation:
- API 26 baseline and API 29+ terminal path as applicable.
- Fresh install, update install, rotation, multi-window, app backgrounding, low-memory/process recreation and interrupted networking.
- SAF local and provider-backed folders, persistent URI permissions and read-only/revoked access.
- Debian rootfs, APT update/install, OpenCode CLI/server, Android SDK and Gradle build.
- Shizuku binder lost/reconnected, permission denied/granted, UserService disconnect and ADB-shell UID.

CI passing is never reported as physical-device verification.

## Current execution state

The current branch has completed the first CI-validated P0 implementation slice. CI run [45829957949](https://github.com/certier/FxForge/actions/runs/45829957949) passed all of the following on commit `943c24e7`:

- ARM64 PRoot compilation and artifact upload.
- `testDebugUnitTest`, including three-way Workspace conflict planning, unsafe-path rejection, isolated build snapshot checks, and current-version verification for explicit conflict deletion choices.
- Debug APK assembly and upload.

The implemented P0 slice now includes per-project private directories keyed by SAF tree URI, persisted per-project sync manifests, verified SAF imports, staged private and SAF file replacement, three-way SHA-256 conflict detection, explicit keep-private/keep-storage resolution, conflict routing for cached projects, restoration after SAF permission is re-granted, and isolated verified build snapshots so Gradle does not mutate the live OpenCode Workspace. OpenCode and Linux terminal start/stop operations are serialized with project switching.

**P0 is not declared complete yet.** Remaining acceptance work includes:
- End-to-end SAF sync tests against real DocumentProvider implementations, including rename failures, revoked grants and process interruption.
- Explicit handling and tests for empty-directory changes and file-versus-directory path collisions.
- A user-facing backup/export and recovery path for private workspaces whose original SAF tree has been removed or whose permission cannot be restored.
- Physical-device testing of project import, conflict resolution, switching, OpenCode, terminal and Gradle builds while the workspace is open.

CI success verifies compilation and JVM tests only; it does not establish that PRoot, Debian, APT, OpenCode or Shizuku work on an Android device.
