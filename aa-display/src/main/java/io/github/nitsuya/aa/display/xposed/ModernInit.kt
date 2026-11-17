package io.github.nitsuya.aa.display.xposed

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
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
 * libxposed (modern Xposed API) entry point, declared in `META-INF/xposed/java_init.list`.
 *
 * Rationale: LSPosed 2.2.0+ deprecates legacy modules that declare nsp with world-readable xml,
 * and 2.3.0 plans to remove that channel. The modern API reads config from the framework daemon
 * over Binder, eliminating the need for a world-readable `aadisplay_config.xml`.
 *
 * ## Construction & Lifecycle Constraints (violations cause load failure or crash)
 *
 * - **Must have a no-arg constructor**: Vector's `VectorModuleManager` calls
 *   `getDeclaredConstructor().newInstance()` and then `attachFramework(...)`.
 *   (Upstream LSPosed 1.9.2 expects `(XposedInterface, ModuleLoadedParam)`; the two cannot
 *   coexist on one class, so legacy falls back to [XposedInit].)
 * - **Do not touch any framework API before `onModuleLoaded`**: every `XposedInterfaceWrapper`
 *   method calls `ensureAttached()` and throws `IllegalStateException` if unattached.
 *   Therefore no properties are eagerly initialized and config is fetched lazily.
 *
 * ## Dispatch
 *
 * - system_server -> [onSystemServerStarting]. Dispatched before `SystemServer.startBootstrapServices`,
 *   earlier than `ServiceManager.addService("package", ...)` needed by [AndroidHook].
 * - Regular apps -> [onPackageReady]. Fires before `Application.onCreate`, so `Instrumentation.callApplicationOnCreate`
 *   hookBefore still works; it fires **multiple times** for split APKs, deduplicated via `BaseHook.isInit`.
 */
class ModernInit : XposedModule() {
    companion object {
        private const val TAG = "AADisplay_ModernInit"
    }

    /**
     * Process name. In the modern API only `ModuleLoadedParam` provides it;
     * `onPackageReady` / `onSystemServerStarting` do not, so store it here.
     */
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        // Remote config: lazy fetch + runCatching. VectorRemotePreferences does a synchronous IPC
        // in init which throws XposedFrameworkError on failure, or UnsupportedOperationException
        // in embedded runtime. Fall back to each config item's default on failure.
        HookPreferences.install(supplier = { getRemotePreferences(AADisplayConfig.ConfigName) })
        installModuleAssets()
        log(TAG, "module loaded: process=$processName, systemServer=${param.isSystemServer}")
    }

    /**
     * Inject the `modulePath` source that `EzXHelperInit.initZygote` handles on the legacy path.
     *
     * The modern API has no `StartupParam` (package-private constructor) and `XposedInterface`
     * does not expose modulePath, so we read `getModuleApplicationInfo().sourceDir`.
     * Register only a supplier here and defer the actual IPC until module resources are first needed —
     * `onModuleLoaded` runs before `SystemServer.startBootstrapServices` in system_server, too early
     * to hit the framework process.
     *
     * Must be injected: `InitFields.modulePath` is lateinit and read when constructing `CommonContextWrapper`
     * on system_server's main looper ([CoreManagerService] creating the floating window); leaving it
     * uninitialized throws `UninitializedPropertyAccessException` -> system_server soft reboot.
     */
    private fun installModuleAssets() {
        ModuleAssets.installPathSupplier { moduleApplicationInfo?.sourceDir }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val target = HookTarget(
            packageName = HookTarget.PACKAGE_ANDROID,
            processName = processName.ifEmpty { "system_server" },
            classLoader = param.classLoader,
            appInfo = null,
        )
        initHooks(target, AndroidHook)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        val target = HookTarget(
            packageName = packageName,
            processName = processName.ifEmpty { packageName },
            classLoader = param.classLoader,
            appInfo = param.applicationInfo,
        )
        hooksFor(target)?.also { initHooks(target, *it) }
    }

    /**
     * Same dispatch rules as [XposedInit.handleLoadPackage], without the system_server branch
     * (handled by [onSystemServerStarting] under the modern API).
     */
    private fun hooksFor(target: HookTarget): Array<BaseHook>? {
        val appInfo = target.appInfo
        return when (target.packageName) {
            "com.google.android.projection.gearhead" -> arrayOf(AndroidAuoHook)
            "com.waze" -> arrayOf(OtherHook, WazeHook)
            // Only attach GboardImeHook: OtherHook zeroes status_bar_height, which is intended
            // for regular apps in projection; the IME process does not need and should not be altered.
            GboardImeHook.PACKAGE_GBOARD -> arrayOf(GboardImeHook)
            // Do not dispatch to the module's own process: LSPosed does not allow selecting the module
            // itself into scope, so this is never called.
            BuildConfig.APPLICATION_ID -> null
            else -> if (appInfo == null || appInfo.uid == 1000) null else arrayOf(OtherHook)
        }
    }

    private fun initHooks(target: HookTarget, vararg hook: BaseHook) {
        // EzXHelperInit.initHandleLoadPackage bytecode only does these two things; call them directly
        // instead of forging a LoadPackageParam with a package-private constructor.
        EzXHelperInit.setEzClassLoader(target.classLoader)
        EzXHelperInit.setHostPackageName(target.packageName)
        hook.forEach {
            runCatching {
                if (it.isInit) return@forEach
                it.init(target)
                it.isInit = true
                log(TAG, "Inited hook: ${it.tagName}")
            }.onFailure { e ->
                log(TAG, "Failed init hook: ${it.tagName}", e)
            }
        }
    }
}
