package io.github.nitsuya.aa.display.xposed.hook

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Framework-agnostic description of a hooked target.
 *
 * After migrating to libxposed (modern API), the two entry points provide completely different param types:
 * - legacy: [XC_LoadPackage.LoadPackageParam] (`public final` with package-private constructor,
 *   cannot be instantiated by the module);
 * - modern: `XposedModuleInterface.PackageReadyParam` / `SystemServerStartingParam`,
 *   and **neither modern callback provides the process name**, which must be captured from `onModuleLoaded`.
 *
 * All hooks therefore accept this unified type, translated by the entry point. Only fields actually
 * used across the codebase are kept: `classLoader`, `processName`, `packageName`, `appInfo` (for `sourceDir` / `uid`).
 */
class HookTarget(
    /** Host package name. system_server keeps legacy `"android"`. */
    val packageName: String,
    /** Process name. On modern side comes from `ModuleLoadedParam.getProcessName()`. */
    val processName: String,
    /** Host ClassLoader. */
    val classLoader: ClassLoader,
    /**
     * Host ApplicationInfo. Null for system_server — legacy identifies system_server via
     * `packageName == "android" && appInfo == null`.
     */
    val appInfo: ApplicationInfo?,
    /**
     * Whether this is the first app in the process. Modern API has no equivalent (`isFirstPackage()`
     * is unavailable in `onPackageReady`); used only for logging, defaults to false.
     */
    val isFirstApplication: Boolean = false,
) {
    /** Whether this target is system_server. */
    val isSystemServer: Boolean get() = packageName == PACKAGE_ANDROID && appInfo == null

    override fun toString(): String =
        "HookTarget(package=$packageName, process=$processName, uid=${appInfo?.uid}, first=$isFirstApplication)"

    companion object {
        const val PACKAGE_ANDROID = "android"

        /** For legacy entry: copy [XC_LoadPackage.LoadPackageParam] as-is. */
        fun of(lpparam: XC_LoadPackage.LoadPackageParam): HookTarget = HookTarget(
            packageName = lpparam.packageName,
            processName = lpparam.processName,
            classLoader = lpparam.classLoader,
            appInfo = lpparam.appInfo,
            isFirstApplication = lpparam.isFirstApplication,
        )
    }
}
