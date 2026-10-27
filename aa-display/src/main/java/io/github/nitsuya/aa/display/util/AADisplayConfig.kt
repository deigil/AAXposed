package io.github.nitsuya.aa.display.util

import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import java.io.StringReader
import java.util.*

sealed class AADisplayConfig<T>(val key: String) {
    companion object {
        const val ConfigName = "aadisplay_config"
    }

    abstract fun get(config: SharedPreferences?): T

    object LauncherPackage: StringConfig("LauncherPackage", null)
    object HomePackage: StringConfig("HomePackage", null)
    object AutoOpen: BooleanConfig("AutoOpen", true)
    object DisableWazeOnAa: BooleanConfig("DisableWazeOnAa", false)
    object DisableGoogleMapsOnAa: BooleanConfig("DisableGoogleMapsOnAa", false)
    object VirtualDisplayDpi: IntConfig("VirtualDisplayDpi", 0)
    object AndroidAutoDpi: IntConfig("AndroidAutoDpi", 0)
    object DelayDestroyTime: IntConfig("DelayDestroyTime", 180)
    object ScreenOffReplaceLockScreen: BooleanConfig("ScreenOffReplaceLockScreen", false)
    object CloseLauncherDashboard: BooleanConfig("CloseLauncherDashboard", true)
    object ForceRightAngle: BooleanConfig("ForceRightAngle", true)
    // true：隐藏车机端 Coolwalk 侧边栏/UI 控件，投屏按整块车机宽度全屏；
    //       控制方案交给手机上的悬浮窗（DisplayWindow）。
    // false：回到旧行为，在车机侧边栏重建 home/全屏/返回/语音助手图标。
    object HideAaUiControls: BooleanConfig("HideAaUiControls", true)
    // 投屏虚拟屏的输入法归属，见 [io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter.onConnected]
    // 0 = DISPLAY_IME_POLICY_LOCAL：键盘画在投屏那块 VirtualDisplay 上（挡掉车机半屏）
    // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY：键盘落到 default display，即手机主屏（默认，与 nitsuya 上游一致）
    // 2 = DISPLAY_IME_POLICY_HIDE：该屏不显示输入法
    object DisplayImePolicy: IntConfig("DisplayImePolicy", 1)
    // MIUI/HyperOS 护眼模式是 SF 层 colorTransformMatrix，只作用于 display 0 后由 SF
    // 广播到所有屏，投屏画面必然跟着变黄，且无 per-display 排除接口。
    // true：投屏期间临时关闭护眼总开关（screen_paper_mode_enabled），断开后按原值恢复；
    //       代价是抑制为全局的 —— 投屏期间手机自己的屏幕也暂时没有护眼效果。
    // 见 [io.github.nitsuya.aa.display.util.EyeCareSuspender]
    object SuspendEyeCareOnProjection: BooleanConfig("SuspendEyeCareOnProjection", true)
    // 竖屏应用（微信、bilibili 等）在投屏这块横屏 VirtualDisplay 上会被 WM 的
    // fixed-orientation letterbox 压成居中窄条，两侧留黑边；display 的
    // ignoreOrientationRequest 对虚拟屏无效（实测切换 true/false 边界不变），
    // 唯一生效的是把 activity 的 override orientation 去掉。
    // true：投屏屏上的 activity 一律按 SCREEN_ORIENTATION_UNSPECIFIED 解析，铺满整屏；
    //       代价是强制竖屏应用要以横屏布局运行，个别写死竖屏的应用可能显示异常。
    // 见 [io.github.nitsuya.aa.display.xposed.hook.AndroidHook.AppFullscreenOnProjection]
    object ForceAppFullscreenOnProjection: BooleanConfig("ForceAppFullscreenOnProjection", true)
    // Gboard 的键盘形态由持久化的 keyboard_mode_landscape 和 float_keyboard_in_* 决定，
    // 而设备形态取自机身（投屏时仍是 DEVICE_PHONE），投屏那块横屏 VirtualDisplay 因此
    // 直接命中“手机 + 横屏”这套键，键盘会以悬浮模式打开。
    // true：投屏期间把 Gboard 读到的悬浮形态改回普通形态（不写任何 Gboard 设置）；
    //       代价是投屏期间手机自己横屏也不会悬浮。
    // 见 [io.github.nitsuya.aa.display.xposed.hook.GboardImeHook]
    object FixGboardFloatingIme: BooleanConfig("FixGboardFloatingIme", true)
    // 空 = 不接管，交给 Android Auto 自带的语音助手；非空 = 点击助手图标/方控语音键时执行的命令。
    // 支持 ${DisplayId} 占位符。设置页会扫描手机上的语音助手并生成命令。
    // （上游默认是小爱同学的 MIUI 专用命令，在非小米手机上无效，所以不再设默认值。）
    object VoiceAssistShell: StringConfig("VoiceAssistShell", null)
    object CreateVirtualDisplayBefore: ArrayStringConfig("CreateVirtualDisplayBefore")
    object DestroyVirtualDisplayAfter: ArrayStringConfig("DestroyVirtualDisplayAfter")
    object ComGoogleAndroidGmsCarProps: PropertiesConfig("ComGoogleAndroidGmsCarProps")
    object ComGoogleAndroidProjectionGearheadProps: PropertiesConfig("ComGoogleAndroidProjectionGearheadProps")


    abstract class StringConfig(key: String, private val defValue: String? = null): AADisplayConfig<String?>(key){
        override fun get(config: SharedPreferences?): String? = config?.getString(key, defValue)?.trim()?.let {
            it.ifBlank { defValue }
        } ?: defValue
    }
    abstract class BooleanConfig(key: String, private val defValue: Boolean = false): AADisplayConfig<Boolean>(key){
        override fun get(config: SharedPreferences?): Boolean = config?.getBoolean(key, defValue) ?: defValue
    }
    abstract class IntConfig(key: String, private val defValue: Int = 0): AADisplayConfig<Int>(key){
        private val defValueStr = defValue.toString()
        override fun get(config: SharedPreferences?): Int = tryOrNull { config?.getString(key, defValueStr)?.toInt() } ?: defValue
    }
    abstract class ArrayStringConfig(key: String): AADisplayConfig<Array<String>>(key){
        override fun get(config: SharedPreferences?): Array<String>{
            return config?.getString(key, null)?.let {
                it.split("\n")
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toTypedArray()
            } ?: emptyArray()
        }
    }
    abstract class PropertiesConfig(key: String): AADisplayConfig<Properties?>(key){
        override fun get(config: SharedPreferences?): Properties?{
            return config?.getString(key, null)?.let {
                Properties().apply {
                    load(StringReader(it))
                }
            } ?: null
        }
    }

}
