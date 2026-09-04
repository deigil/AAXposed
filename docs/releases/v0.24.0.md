# Release Notes v0.24 (17.2-e1)

Date: 2026-08-30
Version Name: `0.24#17.2-e1`
Version Code: `3012`
基线（erxm 分支最初版本）: `c770d65` — `release: v0.23.6 Android Auto 17.2 display hardening`（`0.23#17.2-r5` / `3011`）

## 概述

这一版把 AADisplay 的使用形态从「车机侧边栏控制 + 全屏镜像窗」改成
**「车机整屏投屏 + 手机端 MIUI 小窗控制」**：

- 车机端折叠 Coolwalk 侧边栏，投屏铺满整块车机宽度，不再在车机上重建控制图标；
- 手机端的镜像窗变成可拖动、可等比缩放、卡片外触摸穿透的小窗，所有控制都在这里完成；
- 输入法、护眼模式、Gboard 悬浮键盘这三个影响投屏观感的问题一并修掉；
- 新增独立的 miuix 风格设置页，把开关从菜单里集中出来。

Coolwalk facetbar 相关代码来自 `lagXkjy/AADisplay` 的 `AADisplay-Split-Lite`
分支，通过 `reference` remote 按路径 cherry-pick 引入，保留原作者与提交时间。

## 新增

- **Coolwalk 全屏链路**（导入 + 本地适配）：折叠车机侧边栏、回收 rail 宽度后按整块
  车机宽度重新申请投屏画布；软重连后 rail 可能晚一拍出现或被饿死，加入 settle 重试。
- **`ICoreManager` 的 Coolwalk rail 通道**：gearhead 在 Android 13+ 上无法可靠投递
  定向到包名的广播，所以 `:projection` / `:car` 把 rail 快照上报给 `system_server`
  侧的 `CoreManagerService`，再由系统 uid 广播出去。新增 4 个方法一律追加在 AIDL
  接口末尾，保持既有 Stub 序号不变：
  - `reportCoolwalkRailSnapshot` / `getCoolwalkRailSnapshot`
  - `getCoolwalkReconnectEpochMs` / `notifyCoolwalkFullBleed`
- **手机端小窗多任务面板的图标模式**：`recent_task_icon.xml` 只显示应用图标 + 名称。
- **独立设置页** `SettingsActivity`（miuix 风格，`pref_aadisplay_config.xml`）。
- 新增配置项：
  - `HideAaUiControls`（默认 `true`）：隐藏车机 UI 控件走 Coolwalk 全屏；
    置 `false` 回到旧行为，在车机侧边栏重建 home / 全屏 / 返回 / 语音助手图标。
  - `SuspendEyeCareOnProjection`（默认 `true`）
  - `FixGboardFloatingIme`（默认 `true`）

## 变更

- 版本号 `0.23#17.2-r5` / `3011` → `0.24#17.2-e1` / `3012`。
- **镜像窗形态**：原来铺满全屏并画遮罩，窗外触摸全被吃掉。现在是 `WRAP_CONTENT` 的
  `TYPE_APPLICATION_OVERLAY` 浮窗，`gravity = START|TOP`（`x`/`y` 即左上角绝对偏移），
  卡片之外的触摸直接落回底下的应用；顶栏与底部导航条都是拖动区；右下角把手拖动等比
  缩放、长按恢复默认大小。
  缩放是两因子模型 `mDisplayRatio = mMirrorFitRatio * mMirrorScale`，`sv_mirror` 的
  触摸转发按 `mDisplayRatio` 反算坐标，**任何改变缩放的入口都必须走
  `updateDipslaySize()`**，否则触摸点会偏。`holder.setFixedSize()` 把 buffer 钉在车机
  原生分辨率上，View 侧缩放是纯 GPU 拉伸，不重建 Surface。
- **`DisplayImePolicy` 默认值 `0`（`DISPLAY_IME_POLICY_LOCAL`，且原先被强制）改为 `1`
  （`DISPLAY_IME_POLICY_FALLBACK_DISPLAY`）**：键盘不再画在投屏那块 VirtualDisplay 上
  挡掉车机半屏，而是落回手机主屏。取值收敛在 `0..2`，越界回落到 `1`。
- **`VoiceAssistShell` 默认值**改为小爱语音助手（与 nitsuya 上游一致），支持
  `${DisplayId}` 占位符。
- `XposedInit` 分发调整：自己的进程挂 `AaDisplayProcessHook`；Gboard 进程**只**挂
  `GboardImeHook` —— `OtherHook` 会把 `status_bar_height` 归零，那是给投屏里的普通
  应用用的，输入法进程不该被改。
- 主界面卡片化重排，开关移入设置页。

## 修复

- **小窗多任务面板溢出卡片（用户报的「错位」）**：`ll_recent_task` 之前是
  `match_parent`，而它所在的 `FrameLayout` 是 `wrap_content`；wrap 测量阶段子 View
  拿到的是 `AT_MOST`，`recent_task.xml` 里 9:16 的任务快照缩略图把条目撑得比车机画面
  还高，整张卡片跟着被撑大，面板压到导航条和桌面上。
  修法：小窗改用不加载快照的图标网格；面板尺寸与网格列数由
  `updateRecentTaskPanelSize()` 按 `sv_mirror` 的实际像素在运行时写入；取任务列表时
  传 `withSnapshot = false`，省掉每个任务一次 `getTaskSnapshot` IPC 和
  `HardwareBuffer` 包装。车机端 `AaRecentTaskFragment` 不传 `iconMode`，快照模式不变。
- **投屏画面跟着护眼模式变黄**：MIUI/HyperOS 护眼是 SF 层的 `colorTransformMatrix`，
  只作用于 display 0 后由 SF 广播到所有屏，没有 per-display 排除接口。
  `EyeCareSuspender` 在投屏期间临时关掉 `screen_paper_mode_enabled`，断开按原值恢复。
  代价：投屏期间手机自己也暂时没有护眼效果。
- **投屏里 Gboard 以悬浮键盘打开**：Gboard 的键盘形态取自持久化的
  `keyboard_mode_landscape` / `float_keyboard_in_*`，而设备形态按机身判断（投屏时仍是
  `DEVICE_PHONE`），于是投屏那块横屏 VD 命中「手机 + 横屏」这套键。`GboardImeHook` 只
  改 Gboard 读到的值，不写任何 Gboard 设置。代价：投屏期间手机自己横屏也不会悬浮。
- **`SharedPreferences` 在 LSPosed 未激活时抛 `SecurityException`**：
  `PreferenceManager` 只收一个 mode 常量，且在内部 `getSharedPreferences` 时才抛，
  无法就地 catch。`resolveModeForHooks()` 先探测一次，把可用的 mode 交给
  `PreferenceManager`。
- 连接 / 重连时会闪媒体占位屏（随 Coolwalk 链路导入）。

## 已知取舍

- `SuspendEyeCareOnProjection` 与 `FixGboardFloatingIme` 的抑制都是**全局**的，投屏
  期间手机自身也受影响；两者都可在设置页关掉。
- 车机侧触控接管（`AaCoolwalkHuTouchHook`）**没有启用**，也没有留在树里：本分支的
  控制方案是手机端小窗。需要参考实现时到 `reference/AADisplay-Split-Lite` 查。
- Split-Lite 的应用选择器、蓝牙键鼠、歌词/封面同步等功能**未**引入。

## 验证

测试环境：

| 项目 | 版本 |
|---|---|
| 系统 | HyperOS `3.0.307.6` |
| Android Auto | `17.2` |
| Xposed 框架 | LSPosed |
| 车机端 | DHU（Desktop Head Unit） |

- 构建通过：`:aa-display:assembleDebug`。
- 安装到测试机并重启（改动落在 `system_server`，必须重启才生效）。
- DHU 实机验证：
  - 小窗拖动、等比缩放、长按复位正常（复位日志 `Requested w=1216 h=980`）；
  - 多任务面板在 100% 与缩小状态下都完整落在车机画面区域内，导航条可见；
    列数随可用宽度在 3 / 2 之间自适应；
  - 点击面板里的应用图标能切换车机前台任务并收起面板（`moveTaskToFront` 正常）；
  - logcat 无 `InflateException` / `FATAL EXCEPTION` / `system_server` 异常。

## 引用与致谢

Coolwalk facetbar / 媒体占位屏链路来自 **[lagXkjy/AADisplay]** 的
`AADisplay-Split-Lite` 分支，作者 **qiang `<jqiang.hi@163.com>`**。
本仓库以 `reference` remote 追踪该分支，按路径 cherry-pick 后合入，
每个提交都保留原作者、原提交时间与原始 SHA。

```
git remote -v
origin      https://github.com/erxm/AADisplay.git       # 本 fork
upstream    https://github.com/Stashboy/AADisplay.git   # fork 来源
reference   https://github.com/lagXkjy/AADisplay.git    # 参考实现（push 已禁用）
```

导入范围（其余路径一律不引入）：

```
aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/
aa-display/src/main/java/io/github/nitsuya/aa/display/util/CoolwalkRailStore.kt
aa-display/src/main/java/io/github/nitsuya/aa/display/util/DisplayProfileSettle.kt
aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/AaDisplayProcessHook.kt
aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/AaMediaPlaceholderHook.kt
docs/COOLWALK_FACETBAR.md
```

[lagXkjy/AADisplay]: https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite
