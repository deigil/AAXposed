package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.ProjectionState
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug

/**
 * 投屏时 Gboard 偶发以**悬浮键盘**打开的修复。
 *
 * ## 为什么会发生
 *
 * Gboard 的键盘形态（`KeyboardModeManager` / `KeyboardModeSwitcher`，枚举
 * `1=NORMAL 2=ONE_HANDED 3=FLOATING 4=SPLIT`）由两条路径决定，两条都被投屏触发：
 *
 * 1. **自动悬浮**（`KeyboardModeManager.ap()`）：设备形态不是平板时，若
 *    Phenotype 开关 `enable_auto_float_keyboard_in_landscape` /
 *    `_in_freeform` / `_in_multi_window` 命中，且对应的持久化布尔
 *    `float_keyboard_in_landscape` / `float_keyboard_in_freeform` /
 *    `float_keyboard_in_multi_window` 为 true，则把
 *    `KeyboardModeSwitcher` 的“临时形态”直接置为 3（FLOATING）。
 *    横屏判定就是 `getConfiguration().orientation == ORIENTATION_LANDSCAPE`，
 *    我们的 1280x720 投屏 VirtualDisplay 必然满足。
 *
 * 2. **粘滞悬浮**（`KeyboardModeUtils.h(Context)`）：真正决定开屏形态的是持久化
 *    int `keyboard_mode_landscape`（手机形态 + 横屏时选中的键就是它）。一旦它
 *    曾被写成 3 —— 用户手动选过悬浮，或上一次自动悬浮经
 *    `setKeyboardMode(..., updatePreferences=true)` 落盘 —— 之后**每次**横屏
 *    （含每次投屏）都会以悬浮打开，直到被改回去。这解释了“有时”。
 *
 * 关键点：设备形态取自机身而非渲染所在的 display，投屏时 Gboard 依旧报
 * `DEVICE_PHONE`，所以走的就是上面这套“手机 + 横屏”的键，和手机自己横屏用的是同一批。
 *
 * ## 修复方式
 *
 * 只在**投屏进行中**（[ProjectionState]）拦截 Gboard 自己的 `SharedPreferences`
 * 读取结果：
 * - 键盘形态 int 读到 3（FLOATING）时改成 1（NORMAL）；
 * - `float_keyboard_in_*` 系列布尔读到 true 时改成 false，让 `ap()` 直接返回 false。
 *
 * 挂钩点选 framework 的 `android.app.SharedPreferencesImpl`，而不是 Gboard 混淆后的
 * `Preferences.get(...)`：键名是资源字符串、跨版本稳定，而混淆类名每个版本都在变；
 * 也就不必在输入法进程里为 89MB 的 APK 跑一次 DexKit（内存与启动延迟都不可接受）。
 *
 * **不写入** Gboard 的任何 preference：用户在手机上自己选的悬浮键盘保持原样，
 * 断开投屏后立刻恢复；我们只改变投屏期间的读取结果。
 *
 * 已知取舍：拦截发生在 preference 层，无法区分这次读取是为车机屏还是手机屏做的，
 * 因此**投屏期间手机自己横屏时也不会悬浮**。与护眼抑制（
 * [io.github.nitsuya.aa.display.util.EyeCareSuspender]）同一性质的全局取舍。
 *
 * 另一个已知边界：若 Gboard 在投屏开始**之前**就已经把形态缓存进
 * `KeyboardModeSwitcher`，且整个投屏期间没有触发过配置变化 / 形态刷新，
 * 那次缓存值不会被本 hook 覆盖。实际使用中键盘落到车机 VD 上必然伴随配置变化，
 * 因而会重新走一次读取。
 */
object GboardImeHook : BaseHook() {
    override val tagName: String = "AAD_GboardImeHook"

    const val PACKAGE_GBOARD = "com.google.android.inputmethod.latin"

    /** `KeyboardModeManager` 枚举：1=NORMAL 2=ONE_HANDED 3=FLOATING 4=SPLIT。 */
    private const val MODE_NORMAL = 1
    private const val MODE_FLOATING = 3

    /**
     * 当前 / 上一次键盘形态的持久化键（`KeyboardModePrefUtils.a()/c()` 按
     * 设备形态 + 横竖屏选一个）。投屏走的是 `keyboard_mode_landscape`，
     * 其余一并覆盖，避免“恢复上一形态”把悬浮又拉回来。
     */
    private val KEYBOARD_MODE_INT_KEYS = hashSetOf(
        "keyboard_mode",
        "keyboard_mode_landscape",
        "desk_keyboard_mode",
        "keyboard_mode_foldable_keyboard_mode",
        "keyboard_mode_tablet_keyboard_mode",
        "previous_keyboard_mode",
        "previous_keyboard_mode_landscape",
        "desk_previous_keyboard_mode",
        "keyboard_mode_foldable_previous_keyboard_mode",
        "keyboard_mode_tablet_previous_keyboard_mode",
    )

    /** 自动悬浮开关 + 默认悬浮开关，投屏期间一律视为关闭。 */
    private val FLOAT_BOOL_KEYS = hashSetOf(
        "float_keyboard_in_landscape",
        "float_keyboard_in_freeform",
        "float_keyboard_in_multi_window",
        "float_keyboard_default",
        "desk_float_keyboard_default",
        "keyboard_mode_foldable_float_keyboard_default",
        "tablet_float_keyboard_default",
    )

    /** 配置开关缓存，避免每次 preference 读取都碰 XSharedPreferences。 */
    private var mEnabled: Boolean = true

    /** [ProjectionState] 的本地缓存，由 ContentObserver 维护；热路径不走 ContentProvider。 */
    @Volatile
    private var mProjecting: Boolean = false

    /** observe 注册失败时退化为直接查询（带节流），保证功能不因此完全失效。 */
    @Volatile
    private var mObserved: Boolean = false

    private var mLastPollUptimeMs: Long = 0L

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val configPreferences = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
        mEnabled = if (configPreferences.file.canRead()) {
            runCatching { configPreferences.reload() }
            AADisplayConfig.FixGboardFloatingIme.get(configPreferences)
        } else {
            AADisplayConfig.FixGboardFloatingIme.get(null)
        }
        if (!mEnabled) {
            log(tagName, "disabled by config")
            return
        }

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(android.app.Instrumentation::class.java) {
            name == "callApplicationOnCreate" &&
                parameterCount == 1 &&
                parameterTypes[0] == android.app.Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            startProjectionWatch(InitFields.appContext)
            hookPreferenceReads()
        }
    }

    private fun startProjectionWatch(context: Context) {
        mObserved = ProjectionState.observe(context) { active ->
            if (mProjecting != active) {
                log(tagName, "projection active=$active")
            }
            mProjecting = active
        }
        if (!mObserved) {
            log(tagName, "ProjectionState observe failed; falling back to throttled polling")
            mProjecting = ProjectionState.isActive(context)
        }
    }

    /**
     * observe 失败时的兜底：最多每 2s 查一次 Settings.Global，
     * 避免在每次 preference 读取上打 ContentProvider。
     */
    private fun isProjecting(): Boolean {
        if (mObserved) return mProjecting
        val now = android.os.SystemClock.uptimeMillis()
        if (now - mLastPollUptimeMs > 2_000L) {
            mLastPollUptimeMs = now
            mProjecting = runCatching {
                ProjectionState.isActive(InitFields.appContext)
            }.getOrDefault(false)
        }
        return mProjecting
    }

    private fun hookPreferenceReads() {
        val impl = runCatching { Class.forName("android.app.SharedPreferencesImpl") }
            .onFailure { e -> log(tagName, "SharedPreferencesImpl not found", e) }
            .getOrNull() ?: return

        runCatching {
            findMethod(impl) {
                name == "getInt" &&
                    parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookAfter { param ->
                if (param.result != MODE_FLOATING) return@hookAfter
                val key = param.args[0] as? String ?: return@hookAfter
                if (key !in KEYBOARD_MODE_INT_KEYS) return@hookAfter
                if (!isProjecting()) return@hookAfter
                param.result = MODE_NORMAL
                logDebug(tagName, "keyboard mode $key: FLOATING -> NORMAL")
            }
        }.onFailure { e -> log(tagName, "hook SharedPreferencesImpl.getInt failed", e) }

        runCatching {
            findMethod(impl) {
                name == "getBoolean" &&
                    parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.hookAfter { param ->
                if (param.result != true) return@hookAfter
                val key = param.args[0] as? String ?: return@hookAfter
                if (key !in FLOAT_BOOL_KEYS) return@hookAfter
                if (!isProjecting()) return@hookAfter
                param.result = false
                logDebug(tagName, "auto-float pref $key: true -> false")
            }
        }.onFailure { e -> log(tagName, "hook SharedPreferencesImpl.getBoolean failed", e) }

        log(tagName, "preference read hooks installed")
    }
}
