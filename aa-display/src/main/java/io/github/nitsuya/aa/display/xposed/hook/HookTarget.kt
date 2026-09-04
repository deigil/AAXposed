package io.github.nitsuya.aa.display.xposed.hook

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 与具体 Xposed 框架无关的“被 hook 目标”描述。
 *
 * 迁移到 libxposed（现代 API）后，两条入口给出的参数类型完全不同：
 * - legacy：[XC_LoadPackage.LoadPackageParam]（`public final`，构造器是包私有的，
 *   没法在模块里自己造一个）；
 * - modern：`XposedModuleInterface.PackageReadyParam` / `SystemServerStartingParam`，
 *   而且**两个 modern 回调都不提供进程名**，进程名只能从 `onModuleLoaded` 拿到再存下来。
 *
 * 所以各 hook 统一收本类型，由入口负责把两种参数翻译过来。字段只保留全树实际用到的：
 * `classLoader`、`processName`、`packageName`、`appInfo`（仅为 `sourceDir` / `uid`）。
 */
class HookTarget(
    /** 宿主包名。system_server 沿用 legacy 的写法 `"android"`。 */
    val packageName: String,
    /** 进程名。modern 侧来自 `ModuleLoadedParam.getProcessName()`。 */
    val processName: String,
    /** 宿主 ClassLoader。 */
    val classLoader: ClassLoader,
    /**
     * 宿主 ApplicationInfo。system_server 下为 null —— legacy 就是靠
     * `packageName == "android" && appInfo == null` 认出系统进程的。
     */
    val appInfo: ApplicationInfo?,
    /**
     * 是否是进程里的第一个应用。modern API 没有等价物（`isFirstPackage()` 在
     * `onPackageReady` 阶段拿不到），仅用于日志，缺省 false。
     */
    val isFirstApplication: Boolean = false,
) {
    /** 是否为 system_server。 */
    val isSystemServer: Boolean get() = packageName == PACKAGE_ANDROID && appInfo == null

    override fun toString(): String =
        "HookTarget(package=$packageName, process=$processName, uid=${appInfo?.uid}, first=$isFirstApplication)"

    companion object {
        const val PACKAGE_ANDROID = "android"

        /** legacy 入口用：把 [XC_LoadPackage.LoadPackageParam] 原样搬过来。 */
        fun of(lpparam: XC_LoadPackage.LoadPackageParam): HookTarget = HookTarget(
            packageName = lpparam.packageName,
            processName = lpparam.processName,
            classLoader = lpparam.classLoader,
            appInfo = lpparam.appInfo,
            isFirstApplication = lpparam.isFirstApplication,
        )
    }
}
