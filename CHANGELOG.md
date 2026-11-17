# Changelog

## Unreleased (deigil/AAXposed)

Personal fork for Pixel 9 + Mercedes E53 (MBUX).

### Added
- English translation of the whole UI (`values-en`).
- Advanced settings: DPI (virtual display and Android Auto) is now a preset picker with a
  "Custom…" option instead of a free-text field.
- Advanced settings: the voice assistant is now a picker built from the activities installed on
  the phone (`VOICE_COMMAND` / `ASSIST` / `VOICE_ASSIST`), plus "Android Auto default" and
  "Custom…".
- Manifest `<queries>` for the assistant intents so they are visible on Android 11+.

### Changed
- `VoiceAssistShell` no longer defaults to the Xiaomi-only `com.miui.voiceassist` command. Blank
  now means "don't intercept the Android Auto voice button", which is what the hooks already
  expected but could never reach before.
- README rewritten in English; in-app GitHub menu now points to this repository.


## v0.24#17.2-e2 (2026-09-02)

Baseline: `4a9746b` (tag `v0.24.0`, `0.24#17.2-e1` / `3012`). See [docs/releases/v0.24-17.2-e2.md](docs/releases/v0.24-17.2-e2.md) for details.

### Added
- Migrated to libxposed modern Xposed API: new entry `ModernInit` (`META-INF/xposed/java_init.list`),
  reading remote config from the framework daemon instead of world-readable `aadisplay_config.xml`
  (deprecated in LSPosed 2.2.0, planned for removal in 2.3.0). `XposedInit` is kept as fallback for
  legacy frameworks. `module.prop` `targetApiVersion` stays at 101 so the framework does not block
  `de.robv.android.xposed.*` required by EzXHelper.
- `HookPreferences`: single config entry point on the hook side; source is injected by entry class
  (modern = remote config, legacy = `XSharedPreferences` + `reload()`), resolved once and cached,
  falling back to defaults when unavailable.
- `RemoteConfigMirror`: syncs local `SharedPreferences` written by UI to remote config
  (full backfill on bind, then per-key push).
- `HookTarget`: framework-agnostic target description replacing `LoadPackageParam` in hook signatures
  (neither modern API callback provides processName, and legacy param cannot be instantiated).
- `ModuleAssets`: populates EzXHelper `modulePath` / `moduleRes` (modern API has no `StartupParam`),
  with lazy resolution + `PathClassLoader` fallback, logging without throwing on failure.
- `CoolwalkComposeConstraintsGuard`: Compose constraints assertion guard (see Fixed).
- Added config option `ForceAppFullscreenOnProjection` (default `true`).

### Changed
- Version bumped:
  - `versionName`: `0.24#17.2-e2`
  - `versionCode`: `3016`
- `packaging` no longer excludes `META-INF/**` wholesale; excludes dependency metadata individually
  to preserve `META-INF/xposed/**` required by libxposed.
- Added `android:name=".Application"` to `AndroidManifest.xml`: previously unregistered, so `onCreate`
  never ran and `makeReadableForHooks` / `DynamicColors` / global `App` had never taken effect.
- Module no longer dispatches to its own process: removed `io.github.nitsuya.aa.display` from
  `xposed_scope` and changed the corresponding branch in `XposedInit` to `null`.
- `CoreManagerService.config` now delegates to `HookPreferences`; `AaVirtualDisplayAdapter` config
  parameter relaxed to `SharedPreferences?`.
- `AndroidAuoHook` uses `target.appInfo?.sourceDir` and skips null (previously NPE).
- Removed `AaDisplayProcessHook` / `AaDisplayPresentationResize` and the module-process rewrite path in
  `CoolwalkDrawingSpecWiden`: the module's own process cannot be hooked; projection dimensions are
  adjudicated by `DisplayProfileSettle` in `system_server`.
- Removed unkeyed `content_bounds` rewrite path (process-wide `Rect(int,int,int,int)` / `Rect.set` hooks):
  gearhead's own `Rect(80, 0, 1280, 720)` matched rail-reclaimed content_bounds identically and could
  not be distinguished, expanding to 1280 against a 1200 canvas. Rewrites now only use keyed paths
  (config Bundle / `Bundle.putParcelable` / `Intent.putExtra` / `ArrayMap.put`), and `content_bounds`
  only translates to origin without expanding: `CoolwalkRailMath.expandedBoundsWithin` caps at incoming
  rect width while `CoolwalkDrawingSpecWiden` widens the canvas itself.

### Fixed
- Fixed `gearhead:projection` crashing on DHU / car connect when "Hide head unit UI controls" is enabled:
  that toggle starves GhFacetBar VD to 1x720, Dashboard VD to 1x1, and zeroes leftover window widths;
  on Compose-based 17.x head unit UI, split-pane containers compute the second group's budget as
  `available - firstGroupUsed`, producing negative max when only 0/1 px remains and tripping
  `IllegalArgumentException: maxWidth must be >= than minWidth` in `AndroidComposeView.dispatchDraw`.
  Hooked both validation entry points (17.3 has two), passing valid args straight through and
  clamping only failing tuples (negative max -> 0, min clamped to max), equivalent to 0 available space.
- Fixed portrait apps being squeezed into a centered narrow strip by fixed-orientation letterboxing on
  the landscape head unit: hooked `ActivityRecord.getOverrideOrientation()` (12/13 fallback
  `getOrientation(int)`) to return `SCREEN_ORIENTATION_UNSPECIFIED` on the projection display.
  `setIgnoreOrientationRequest` and `OVERRIDE_ANY_ORIENTATION` had no effect on virtual displays.
- Fixed two causes of system soft reboot (`je#system_server`) during projection: `InitFields.modulePath`
  uninitialized throwing `UninitializedPropertyAccessException` in `CommonContextWrapper`, and
  floating window creation exceptions in `onCreateDisplay` escaping `GlobalScope`.
- `WazeHook` / `GboardImeHook` no longer hold their own `XSharedPreferences`.

### Verification
- Build passed: `:aa-display:assembleRelease`; verified `versionCode=3016` on device.
- Environment: HyperOS `3.0.307.6` (Android 16) + Android Auto `17.3.662854` + LSPosed + DHU.
- Verified `META-INF/xposed/{java_init.list,module.prop,scope.list}` and `assets/xposed_init` in APK;
  modern entry loaded in both `system` and gearhead, `ModuleAssets` resolved modulePath, and
  `RemoteConfigMirror` bound the framework service and synced 16 keys.
- Ran full DHU session after reboot (`HideAaUiControls` on): no `maxWidth must be` or `FATAL EXCEPTION`
  in logcat, no `Failed to read from transport` on DHU, `:projection` and all other four gearhead
  processes stayed alive with unchanged PIDs. Guard triggered once on the expected split-pane path.
- Canvas (run before `CoolwalkHuScreenParamsHook`): `displayProfile locked: 1200*720,160`,
  `content_bounds=Rect(0, 0 - 1200, 720)`, `pillar_width=0`, rail width reclaimed to 0.
- Projected apps (car launcher / AMap): `mBounds=Rect(0, 0 - 1200, 720)`, `letterBoxed=false`.
- Right-edge black bar: after adding `CoolwalkHuScreenParamsHook`, `:car` read full 1280 HU width
  and `CoolwalkProjectionBoundsHook` / `CoolwalkDrawingSpecWiden` expanded content slot back to 1280.
- Two consecutive reboots with no `system_server` soft reboots.

## 0.24 (2026-08-30)

Baseline: `c770d65` (`0.23#17.2-r5` / `3011`). See [docs/releases/v0.24.0.md](docs/releases/v0.24.0.md) for details.

### Added
- Coolwalk full-screen pipeline: collapses head unit sidebar, reclaims rail width, and re-requests
  projection canvas at full HU width; retries settle if rail appears late or is starved after soft
  reconnects. (Imported from `lagXkjy/AADisplay@AADisplay-Split-Lite`, author qiang, preserving history.)
- Added Coolwalk rail channel to `ICoreManager`, relaying full-bleed signal via `system_server`
  (gearhead cannot reliably deliver targeted broadcasts on Android 13+); 4 methods appended at end
  preserving existing Stub ordinals: `reportCoolwalkRailSnapshot` / `getCoolwalkRailSnapshot` /
  `getCoolwalkReconnectEpochMs` / `notifyCoolwalkFullBleed`.
- Icon mode for phone floating window recents panel (`recent_task_icon.xml`, skips task snapshots).
- Standalone miuix-style settings page `SettingsActivity`.
- Added config options `HideAaUiControls` (default true), `SuspendEyeCareOnProjection` (default true),
  `FixGboardFloatingIme` (default true).

### Changed
- Version bumped:
  - `versionName`: `0.24#17.2-e1`
  - `versionCode`: `3012`
- Phone mirror window changed to MIUI freeform style: `WRAP_CONTENT` floating window, outside touch
  pass-through, draggable top/nav bars, proportional resize via bottom-right handle, long-press reset.
  Two-factor scaling `mDisplayRatio = mMirrorFitRatio * mMirrorScale`; all resize paths must go through
  `updateDipslaySize()` so touch forwarding stays in sync.
- `DisplayImePolicy` default changed from forced `LOCAL(0)` to `FALLBACK_DISPLAY(1)` so keyboard falls
  back to phone main screen instead of covering half the head unit; clamped to `0..2`.
- `VoiceAssistShell` default changed to XiaoAi (matching upstream nitsuya).
- `XposedInit` dispatch updated: own process attaches `AaDisplayProcessHook`; Gboard process only
  attaches `GboardImeHook`, avoiding `OtherHook` zeroing `status_bar_height`.
- Main screen reorganized into cards; switches moved to settings page.

### Fixed
- Fixed floating window recents panel overflowing card ("misalignment"): switched from `match_parent`
  snapshot cards to an icon grid sized at runtime from `sv_mirror` pixels, using `withSnapshot = false`
  to avoid per-task `getTaskSnapshot` IPCs. Head unit snapshot mode unchanged.
- Fixed projection turning yellow with MIUI/HyperOS eye-care mode: eye-care is a global SF
  `colorTransformMatrix` with no per-display exclusion; temporarily disabled during projection and restored on disconnect.
- Fixed Gboard opening as floating keyboard during projection: overrides Gboard preference reads without writing settings.
- Fixed `PreferenceManager` throwing uncatchable `SecurityException` when LSPosed is inactive:
  `resolveModeForHooks()` probes an available mode first.
- Fixed media placeholder flashing on connect/reconnect (imported with Coolwalk pipeline).

### Removed
- Removed `AaCoolwalkHuTouchHook.kt` imported with the Coolwalk directory: this branch uses the phone
  floating window instead of taking over head unit touch. See `reference/AADisplay-Split-Lite`.

### Verification
- Build passed: `:aa-display:assembleDebug`
- Environment: HyperOS `3.0.307.6` + Android Auto `17.2` + LSPosed + DHU
- Installed and rebooted test device (`system_server` changes require reboot)
- Verified on DHU: floating window drag / resize / long-press reset work; recents panel fits within
  head unit bounds at both 100% and shrunk sizes (3 / 2 adaptive columns); tapping icons switches
  foreground task; no `InflateException` / `FATAL EXCEPTION` / `system_server` errors in logcat.

## 0.23.6 (2026-07-26)

### Changed
- Production version updated to:
  - `versionName`: `0.23#17.2-r5`
  - `versionCode`: `3011`
- AADisplay now creates its virtual display with the active Android Auto `TextureView` surface immediately instead of creating a surface-less display and attaching later.
- Default launcher startup now resolves from installed home launchers at runtime when no explicit launch package is configured, avoiding stale hard-coded package defaults.

### Fixed
- Fixed blank AADisplay rendering on Android Auto `17.2` by preserving the `TextureView` surface, attaching it during virtual-display creation, and reattaching it on reconnect.
- Prevented duplicate virtual display creation during rapid Android Auto lifecycle callbacks.
- Made hook preference access resilient under LSPosed by committing preferences synchronously and verifying hook-readable preference files.
- Android Auto hooks now continue with safe defaults if preference access is temporarily unavailable instead of aborting all hook setup.

### Verification
- Built successfully:
  - `:aa-display:assembleRelease`
- Installed and reboot-tested on the connected device.
- Live Android Auto validation confirmed:
  - AADisplay side control panel works
  - on-device Android Auto controls work
  - AADisplay opens and renders launcher content
  - backend virtual display and AA-hosted display both showed the same rendered content

## 0.23.4 (2026-05-23)

### Added
- Separate production toggle for Google Maps-on-phone while Android Auto remains connected:
  - new persisted setting `DisableGoogleMapsOnAa`
  - component-level enable/disable manager for Maps projection services + ghost activity

### Changed
- Production version updated to:
  - `versionName`: `0.23#16.8-r1`
  - `versionCode`: `3006`
- Waze component toggle execution now uses the same success gating strategy as Google Maps (all component operations must succeed before returning success).

### Fixed
- Android Auto UI/resource hook stability:
  - `AaUiHook` now skips optional hooks when required resources are missing instead of asserting/crashing.
  - projection decoration constructor hook now uses compatible constructor matching rather than hard-coded constructor lookup.
- Gearhead phenotype property hook robustness:
  - `AaPropsHook` now resolves string fields dynamically to reduce obfuscation fragility.

### Cleanup
- Removed dead/unused code and assets not used by current production flow:
  - removed `AaControlService`, `AaCarService`, `SettingsActivity`, legacy `LauncherHook`, deprecated pref XML, and orphaned resources.
- Removed obsolete global build-feature flag from `gradle.properties` (now explicitly configured in module build config).

### Evidence / Validation
- Built successfully:
  - `:aa-display:assembleRelease`
  - `:aa-display:lintDebug`
- Verified on connected device app targets:
  - Android Auto `16.8.661854-release`
  - Google Maps `26.20.01.913318892`
  - Waze `5.18.5.6`
- Waze and Maps toggles validated as separate independent controls in app flow.

## 0.23.3 (2026-05-05)

### Added
- Smart-sidebar style controller flow:
  - floating controller now starts minimized by default
  - tap edge handle to open, tap close to hide, drag and snap left/right edge

### Changed
- Keyboard routing on virtual display now enforces local IME policy (`DisplayImePolicy=0`) for on-screen input on AADisplay side instead of fallback-to-phone behavior.

### Fixed
- Removed the non-functional disconnect-type (`A`) button from the floating controller.
- Disconnect countdown now replaces the monitor button slot directly during disconnect flow for a cleaner 2-control vertical stack.

### Verification
- `:aa-display:assembleDebug` passed after controller + IME policy updates.
- Installed successfully to test device for live verification.

## 0.23.2 (2026-05-05)

### Changed
- Release build signing now falls back to debug signing when `KEY_ANDROID` is not present, so maintainers can still produce a release artifact locally without exposing private signing credentials.

### Fixed
- Prevented DHU disconnect teardown from forcing the phone back to launcher/home when AADisplay session closes on the virtual-display home screen.
- Teardown package-stop logic now excludes:
  - AADisplay itself
  - configured launch/home package
  - current foreground package on the primary phone display

### Verification
- Reproduced issue with config where `LauncherPackage` and `HomePackage` were both launcher-based, then validated corrected behavior after patch.
- `:aa-display:assembleDebug` succeeds with this change.
- Installed and prepared for live device retest flow.

## 0.23.1 (2026-05-04)

### Added
- In-app GitHub menu now points to the production fork: `https://github.com/Stashboy/AADisplay`.

### Changed
- Internal AADisplay behavior now syncs `HomePackage` to `LauncherPackage` so task-view home behavior is consistent with selected launch package.
- Sidebar Back key handling now uses Android virtual-display key event semantics (`FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`) for more reliable back navigation.

### Fixed
- Sidebar Back now works reliably in task-view sessions.
- Backing out to AADisplay home now clears pinned PiP state using the same cleanup strategy as Home (with front-home checks).

### Cleanup / Optimization (Evidence-Based)
- Fixed a real API-compatibility risk in `AADisplayConfig`: replaced Java Stream `.toList()` usage (API 34+) with Kotlin collection operators (safe for min SDK 31).
- Added missing `super.onDestroy()` in `AaControlService` to resolve lifecycle correctness lint error.
- Removed unused settings warning view/resources and unused string bloat in main settings screen.
- Internationalization cleanup for top-right menu title (`GitHub` moved to string resource).

### Verification
- `:aa-display:assembleDebug` succeeds after all changes.
- `:aa-display:lintDebug` re-run confirms resolved issues for:
  - `MissingSuperCall` (AaControlService)
  - `NewApi` (AADisplayConfig stream/toList path)
  - `HardcodedText` (`menu_main.xml` GitHub title)
- Runtime verification completed against Android Auto `16.6` behavior in both DHU and real head unit sessions.

## 0.23.0 (2026-05-04)

### Added
- Dynamic TaskView sizing lock to stabilize UI rendering across different head unit dimensions.
- Better teardown behavior for TaskView sessions to reduce lingering app states on disconnect/exit.
- Android Auto 16.6 hook compatibility updates.

### Changed
- Simplified settings UI to a single screen with only production-relevant options:
  - Auto Open
  - Default Launch Package
  - Delay Destroy Time
- Default GitHub menu flow retained from the main screen (settings gear flow removed).
- Production version string updated to `0.23#16.6-r1`.

### Fixed
- Kernel panic scenario during specific TaskView lifecycle transitions.
- Home/exit transition handling that previously left abnormal PiP-like states.
- Inconsistent YouTube rendering outcomes caused by unstable virtual display dimensions.

### Security / Privacy
- Removed repository-tracked signing key material from source control.
- Added keystore patterns to `.gitignore`.
- Removed machine-specific Gradle JDK path from tracked config.

### Compatibility
- Validated behavior against Android Auto 16.6 and device-side testing with real head unit workflow.
