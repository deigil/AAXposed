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
    // true: hide head unit Coolwalk sidebar/UI controls so projection uses full head unit width;
    //       controls are handled by the phone floating window (DisplayWindow).
    // false: legacy behavior, rebuild home/fullscreen/back/voice-assist icons in the head unit sidebar.
    object HideAaUiControls: BooleanConfig("HideAaUiControls", false)
    // IME display assignment for projection virtual display, see [io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter.onConnected]
    // 0 = DISPLAY_IME_POLICY_LOCAL: draw keyboard on the projection VirtualDisplay (covers half the head unit)
    // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY: route keyboard to default display, i.e. phone main screen (default, matches upstream nitsuya)
    // 2 = DISPLAY_IME_POLICY_HIDE: do not show IME on this display
    object DisplayImePolicy: IntConfig("DisplayImePolicy", 1)
    // MIUI/HyperOS eye-care mode is a SurfaceFlinger colorTransformMatrix applied to display 0 and
    // broadcast to all displays, tinting the projection yellow with no per-display exclusion API.
    // true: temporarily disable eye-care master toggle (screen_paper_mode_enabled) during projection
    //       and restore on disconnect; trade-off: suppression is global so phone screen also loses eye-care during projection.
    // See [io.github.nitsuya.aa.display.util.EyeCareSuspender]
    object SuspendEyeCareOnProjection: BooleanConfig("SuspendEyeCareOnProjection", true)
    // Portrait-only apps (WeChat, Bilibili, etc.) on the landscape projection VirtualDisplay get
    // letterboxed into a narrow centered strip by WM fixed-orientation letterboxing; display's
    // ignoreOrientationRequest has no effect on virtual displays, so we strip the activity's override orientation.
    // true: resolve activities on the projection display as SCREEN_ORIENTATION_UNSPECIFIED to fill the screen;
    //       trade-off: forces portrait apps to run in landscape layout, which may look off in hardcoded-portrait apps.
    // See [io.github.nitsuya.aa.display.xposed.hook.AndroidHook.AppFullscreenOnProjection]
    object ForceAppFullscreenOnProjection: BooleanConfig("ForceAppFullscreenOnProjection", true)
    // Gboard's keyboard mode is determined by persisted keyboard_mode_landscape and float_keyboard_in_*
    // while device form factor comes from the phone (DEVICE_PHONE), causing the landscape VirtualDisplay
    // to match "phone + landscape" and open Gboard in floating mode.
    // true: override Gboard's read floating state back to normal mode during projection (without writing Gboard settings);
    //       trade-off: phone landscape also won't use floating mode during projection.
    // See [io.github.nitsuya.aa.display.xposed.hook.GboardImeHook]
    object FixGboardFloatingIme: BooleanConfig("FixGboardFloatingIme", true)
    // Empty = do not intercept, hand over to Android Auto's built-in voice assistant;
    // non-empty = shell command to execute when tapping assistant icon / steering wheel voice key.
    // Supports ${DisplayId} placeholder. Settings scans installed voice assistants and generates the command.
    // (Upstream defaulted to XiaoAi MIUI-specific command which fails on non-Xiaomi phones, so no default is set.)
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
