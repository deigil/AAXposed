package io.github.nitsuya.aa.display.xposed

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log.logexIfThrow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AndroidAuoHook
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.BaseHook
import io.github.nitsuya.aa.display.xposed.hook.GboardImeHook
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.hook.OtherHook
import io.github.nitsuya.aa.display.xposed.hook.WazeHook

/**
 * 旧版 Xposed / LSPosed 的兼容入口，由 `assets/xposed_init` 指名。
 *
 * 现役框架走 [ModernInit]（`META-INF/xposed/java_init.list`）。这个类保留是为了
 * 在只认 legacy 通道的旧框架（如上游 LSPosed 1.9.2）上仍能加载：它们的
 * `getModuleApkPath` 接受任一标记，但 modern 入口要的两参构造器与
 * Vector 要的无参构造器互斥，只能靠两个入口类分别覆盖。
 *
 * 这条路径上配置只能来自 `XSharedPreferences`（需要 others 可读的 xml），
 * 所以 [SharedPreferencesAccess][io.github.nitsuya.aa.display.util.SharedPreferencesAccess]
 * 的兼容写法仅为它而留。
 */
class XposedInit : IXposedHookZygoteInit, IXposedHookLoadPackage{
    companion object {
        const val TAG = "AADisplay_XposedInit"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        when{
            packageName == "android" && lpparam.appInfo == null -> arrayOf(AndroidHook)
            packageName == "com.google.android.projection.gearhead" -> arrayOf(AndroidAuoHook)
            packageName == "com.waze" -> arrayOf(OtherHook, WazeHook)
            // 只挂 GboardImeHook：OtherHook 会把 status_bar_height 归零，
            // 那是给投屏里的普通应用用的，输入法进程不需要也不该被改。
            packageName == GboardImeHook.PACKAGE_GBOARD -> arrayOf(GboardImeHook)
            // 模块自身进程不分发：LSPosed 不允许把模块自己选进作用域。
            packageName == BuildConfig.APPLICATION_ID -> null
            lpparam.appInfo == null || lpparam.appInfo.uid == 1000 -> null
            else -> arrayOf(OtherHook)
        }?.also {
            initHooks(lpparam, *it)
        }
    }

    private fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam, vararg hook: BaseHook) {
        EzXHelperInit.initHandleLoadPackage(lpparam)
        installPreferences()
        val target = HookTarget.of(lpparam)
        hook.forEach {
            runCatching {
                if (it.isInit) return@forEach
                it.init(target)
                it.isInit = true
                log(TAG, "Inited hook: ${it.tagName}")
            }.logexIfThrow("Failed init hook: ${it.tagName}")
        }
    }

    /** legacy 侧的配置来源：world-readable xml + 显式 `reload()`。 */
    private fun installPreferences() {
        HookPreferences.install(
            supplier = {
                XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName).takeIf { prefs ->
                    prefs.file.canRead().also { readable ->
                        if (!readable) log(TAG, "config unreadable: ${prefs.file}; using defaults")
                    }
                }
            },
            refresher = { prefs -> (prefs as? XSharedPreferences)?.reload() },
        )
    }
}
