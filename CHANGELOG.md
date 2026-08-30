# Changelog

## 0.24 (2026-08-30)

基线：`c770d65` (`0.23#17.2-r5` / `3011`)。详见 `RELEASE_NOTES_v0.24.0.md`。

### Added
- Coolwalk 全屏链路：折叠车机侧边栏，回收 rail 宽度后按整块车机宽度重新申请投屏画布；
  软重连后 rail 晚一拍出现 / 被饿死时会重试 settle。
  （导入自 `lagXkjy/AADisplay@AADisplay-Split-Lite`，作者 qiang，按路径 cherry-pick 保留原提交历史）
- `ICoreManager` 新增 Coolwalk rail 通道，全屏信号经 `system_server` 中转
  （gearhead 在 Android 13+ 无法可靠投递定向广播）；4 个新方法追加在接口末尾，
  既有 Stub 序号不变：`reportCoolwalkRailSnapshot` / `getCoolwalkRailSnapshot` /
  `getCoolwalkReconnectEpochMs` / `notifyCoolwalkFullBleed`。
- 手机端小窗多任务面板的图标模式（`recent_task_icon.xml`，不加载任务快照）。
- 独立的 miuix 风格设置页 `SettingsActivity`。
- 新增配置项 `HideAaUiControls`（默认 true）、`SuspendEyeCareOnProjection`（默认 true）、
  `FixGboardFloatingIme`（默认 true）。

### Changed
- 版本号更新为：
  - `versionName`: `0.24#17.2-e1`
  - `versionCode`: `3012`
- 手机端镜像窗改为 MIUI 小窗形态：`WRAP_CONTENT` 浮窗、卡片外触摸穿透、顶栏/导航条可拖动、
  右下角把手等比缩放、长按复位。缩放为两因子模型
  `mDisplayRatio = mMirrorFitRatio * mMirrorScale`，触摸转发按该比例反算坐标，
  所有缩放入口都必须走 `updateDipslaySize()`。
- `DisplayImePolicy` 默认由强制 `LOCAL(0)` 改为 `FALLBACK_DISPLAY(1)`，键盘落回手机主屏，
  不再挡掉车机半屏；取值收敛在 `0..2`。
- `VoiceAssistShell` 默认值改为小爱语音助手（与 nitsuya 上游一致）。
- `XposedInit` 分发调整：自身进程挂 `AaDisplayProcessHook`；Gboard 进程只挂
  `GboardImeHook`，不再套用会把 `status_bar_height` 归零的 `OtherHook`。
- 主界面卡片化重排，开关移入设置页。

### Fixed
- 修复小窗多任务面板溢出卡片（「错位」）：`ll_recent_task` 原为 `match_parent` 而父
  `FrameLayout` 是 `wrap_content`，wrap 测量阶段 9:16 快照缩略图把条目撑得比车机画面还高。
  改为图标网格 + 运行时按 `sv_mirror` 实际像素写入面板尺寸与列数，并以
  `withSnapshot = false` 省掉逐任务的 `getTaskSnapshot` IPC。车机端快照模式不变。
- 修复投屏画面跟随 MIUI/HyperOS 护眼模式变黄：护眼是 SF 层全局
  `colorTransformMatrix`，无 per-display 排除接口，投屏期间临时关闭并在断开后按原值恢复。
- 修复投屏里 Gboard 以悬浮键盘打开：只改 Gboard 读到的键盘形态，不写 Gboard 设置。
- 修复 LSPosed 未激活时 `PreferenceManager` 内部抛 `SecurityException` 无法就地 catch：
  `resolveModeForHooks()` 先探测可用 mode 再交给 `PreferenceManager`。
- 修复连接 / 重连时闪媒体占位屏（随 Coolwalk 链路导入）。

### Removed
- 删除随 Coolwalk 目录导入的 `AaCoolwalkHuTouchHook.kt`：本分支不接管车机侧触控，
  控制走手机端小窗，留着是死代码。参考实现见 `reference/AADisplay-Split-Lite`。

### Verification
- 构建通过：`:aa-display:assembleDebug`
- 安装并重启测试机（`system_server` 侧改动需重启生效）
- DHU 实机验证：小窗拖动 / 缩放 / 长按复位正常；多任务面板在 100% 与缩小状态下均完整
  落在车机画面区域内，列数在 3 / 2 之间自适应；点击图标能切换车机前台任务；
  logcat 无 `InflateException` / `FATAL EXCEPTION` / `system_server` 异常。

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
