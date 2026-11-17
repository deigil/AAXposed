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
 * Compatibility entry point for legacy Xposed / LSPosed, referenced by `assets/xposed_init`.
 *
 * Active frameworks use [ModernInit] (`META-INF/xposed/java_init.list`). This class is kept so
 * legacy frameworks that only recognize the legacy channel (such as upstream LSPosed 1.9.2) can
 * still load the module: their `getModuleApkPath` accepts either marker, but the two-arg constructor
 * required by modern entry and the no-arg constructor required by Vector are mutually exclusive,
 * requiring two separate entry classes.
 *
 * On this path config can only come from `XSharedPreferences` (requiring world-readable xml),
 * so [SharedPreferencesAccess][io.github.nitsuya.aa.display.util.SharedPreferencesAccess]'s
 * compatibility logic is kept solely for it.
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
            // Only attach GboardImeHook: OtherHook zeroes status_bar_height, which is intended
            // for regular apps in projection; the IME process does not need and should not be altered.
            packageName == GboardImeHook.PACKAGE_GBOARD -> arrayOf(GboardImeHook)
            // Do not dispatch to the module's own process: LSPosed does not allow selecting the module itself into scope.
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

    /** Legacy config source: world-readable xml + explicit `reload()`. */
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
