# AADisplay

[![Fork](https://img.shields.io/badge/Fork-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 31](https://img.shields.io/badge/Android%20SDK-%3E%3D%2031-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)

把手机上的一块虚拟屏投到 Android Auto 车机上，车机整屏显示投屏内容，
控制则留在手机端的悬浮小窗上。

本仓库是 [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) 的 fork，
面向 Android Auto 17.x 兼容性、TaskView 生命周期稳定性和投屏尺寸的正确性。

## 版本

- 当前模块版本：`0.24#17.2-e2`（versionCode `3016`）
- 详见 [CHANGELOG.md](CHANGELOG.md) 与 [RELEASE_NOTES_v0.24#17.2-e2.md](RELEASE_NOTES_v0.24%2317.2-e2.md)

## 运行要求

- Android 12 及以上（SDK 31+）
- 已 root，并安装 LSPosed（或兼容的 Xposed 框架）
- Android Auto（`com.google.android.projection.gearhead`）

## 测试环境

`0.24#17.2-e2` 在以下环境实测通过：

| 项目 | 版本 |
|---|---|
| 系统 | HyperOS `3.0.307.6` |
| Android Auto | `17.3.662854` |
| Xposed 框架 | LSPosed |
| 车机端 | DHU（Desktop Head Unit） |

已在该环境验证的内容：

- 手机端小窗可拖动、可等比缩放，长按缩放把手恢复默认大小；
- 多任务面板在 100% 与缩小状态下都完整落在车机画面区域内，网格列数随可用宽度
  在 3 / 2 之间自适应；点击应用图标能切换车机前台任务；
- 「隐藏车机 UI 控件」开启时整段 DHU 会话不再闪退，rail 回收为 0；投屏画布先落在内容槽
  `1200x720`，`CoolwalkHuScreenParamsHook` 读到车机全宽后撑回 `1280x720`（日志验证）；
- 投屏屏上的应用铺满整屏（`letterBoxed=false`），不再被 letterbox 压成居中窄条；
- 投屏期间护眼模式抑制、Gboard 键盘落回手机主屏均按预期生效；
- logcat 无 `InflateException` / `FATAL EXCEPTION` / `system_server` 异常。

关于 HyperOS 的两点说明（都是系统机制导致的，不是模块 bug）：

- **护眼模式**是 SurfaceFlinger 层的全局 `colorTransformMatrix`，只作用于
  display 0 后由 SF 广播到所有屏，没有 per-display 排除接口，投屏画面必然跟着变黄。
  模块默认开启 `SuspendEyeCareOnProjection`，投屏期间临时关闭护眼总开关、断开后按
  原值恢复；代价是投屏期间手机自己的屏幕也暂时没有护眼效果。
- **Gboard 悬浮键盘**：Gboard 的键盘形态取自持久化的 `keyboard_mode_landscape` /
  `float_keyboard_in_*`，而设备形态按机身判断（投屏时仍是 `DEVICE_PHONE`），投屏那块
  横屏虚拟屏因此命中「手机 + 横屏」这套键，键盘会以悬浮形态打开。默认开启
  `FixGboardFloatingIme` 修正，只改 Gboard 读到的值，不写任何 Gboard 设置；代价是投屏
  期间手机自己横屏也不会悬浮。

其他机型 / 其他 HyperOS 版本未做验证。由于本模块 hook 了 `system_server`，
**每次更新模块后都必须重启设备才能生效**。

## 快速开始

1. 编译并安装 APK。
2. 在 LSPosed 中为以下作用域启用模块：
   - Android Auto
   - 系统框架（System Framework）
3. 重启设备。
4. 打开 AADisplay 进行配置：
   - 自动打开（Auto Open）
   - 连接 AA 时禁用 Waze
   - 连接 AA 时禁用 Google 地图
   - 默认启动应用
   - 延迟销毁时间
   - 隐藏车机 UI 控件（`HideAaUiControls`，默认开）
   - 投屏应用强制全屏（`ForceAppFullscreenOnProjection`，默认开）
   - 投屏时挂起护眼模式（`SuspendEyeCareOnProjection`，默认开）
   - 修正 Gboard 悬浮键盘（`FixGboardFloatingIme`，默认开）

## 编译

```bash
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

## Remote 结构与引用致谢

```
origin      https://github.com/erxm/AADisplay.git       # 本 fork
upstream    https://github.com/Stashboy/AADisplay.git   # fork 来源
reference   https://github.com/lagXkjy/AADisplay.git    # 参考实现（push 已禁用）
```

Coolwalk facetbar / 媒体占位屏链路来自
[lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite)
的 `AADisplay-Split-Lite` 分支，作者 **qiang** `<jqiang.hi@163.com>`。
引入方式是按路径 cherry-pick 到 `vendor/coolwalk-import` 分支后合入 `main`，
每个提交都保留原作者、原提交时间与原始 SHA。后续刷新导入：

```bash
git fetch reference AADisplay-Split-Lite
git log --oneline main..reference/AADisplay-Split-Lite -- \
    aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/
```

未引入的部分：自定义应用选择器、蓝牙键鼠支持、歌词 / 封面同步链路。
`AaCoolwalkHuTouchHook`（车机侧触控接管）也刻意不带 —— 本 fork 的控制方案是手机端悬浮小窗。

## 许可证

继承上游，见 [LICENSE](LICENSE)。
