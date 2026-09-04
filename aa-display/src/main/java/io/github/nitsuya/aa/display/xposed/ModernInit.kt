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
 * libxposed（现代 Xposed API）入口，由 `META-INF/xposed/java_init.list` 指名。
 *
 * 存在的理由：LSPosed 2.2.0 起对“声明 nsp 且存在 others 可读 xml”的 legacy 模块
 * 打废弃标记，2.3.0 计划移除该通道。现代 API 用 Binder 读框架守护进程里的配置，
 * 不再需要世界可读的 `aadisplay_config.xml`。
 *
 * ## 构造与生命周期约束（都会导致加载失败或崩溃，不是风格问题）
 *
 * - **必须有无参构造器**：Vector 的 `VectorModuleManager` 走
 *   `getDeclaredConstructor().newInstance()`，随后才 `attachFramework(...)`。
 *   （上游 LSPosed 1.9.2 要的是 `(XposedInterface, ModuleLoadedParam)` 两参构造器，
 *   两者无法由同一个类同时满足；旧版走 [XposedInit] 那条 legacy 路径兜底。）
 * - **`onModuleLoaded` 之前不能碰任何框架 API**：`XposedInterfaceWrapper` 的每个
 *   方法都先 `ensureAttached()`，未 attach 就抛 `IllegalStateException`。
 *   所以这里没有任何 eager 初始化的属性，配置也是懒取。
 *
 * ## 分发
 *
 * - system_server → [onSystemServerStarting]。框架在
 *   `SystemServer.startBootstrapServices` 之前分发，早于 [AndroidHook] 需要的
 *   `ServiceManager.addService("package", …)`。
 * - 普通应用 → [onPackageReady]。该回调在 `Application.onCreate` 之前触发，
 *   所以各 hook 里 `Instrumentation.callApplicationOnCreate` 的 hookBefore 依旧有效；
 *   但它对 split APK 会**重复**触发，因此靠 `BaseHook.isInit` 去重。
 */
class ModernInit : XposedModule() {
    companion object {
        private const val TAG = "AADisplay_ModernInit"
    }

    /**
     * 进程名。现代 API 只有 `ModuleLoadedParam` 提供进程名，
     * `onPackageReady` / `onSystemServerStarting` 都不给，只能在这里存下来。
     */
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        // 远端配置：懒取 + runCatching。VectorRemotePreferences 在 init 里就做一次
        // 同步 IPC，失败会抛 XposedFrameworkError；框架内嵌运行时还会抛
        // UnsupportedOperationException。取不到就一律走各配置项的默认值。
        HookPreferences.install(supplier = { getRemotePreferences(AADisplayConfig.ConfigName) })
        installModuleAssets()
        log(TAG, "module loaded: process=$processName, systemServer=${param.isSystemServer}")
    }

    /**
     * 注入 legacy 路径由 `EzXHelperInit.initZygote` 负责的 `modulePath` 来源。
     *
     * 现代 API 没有 `StartupParam`（构造器包私有），`XposedInterface` 也不给 modulePath，
     * 只能从 `getModuleApplicationInfo().sourceDir` 取模块 APK 路径。
     * 这里只注册回调，实际 IPC 推迟到第一次真正需要模块资源时 —— `onModuleLoaded`
     * 在 system_server 里早于 `SystemServer.startBootstrapServices`，不宜在那时打框架进程。
     *
     * 必须注入：`InitFields.modulePath` 是 lateinit，`CommonContextWrapper` 构造时读它，
     * 而那个构造发生在 system_server 主 looper 上（[CoreManagerService] 建投屏悬浮窗），
     * 未初始化就抛 `UninitializedPropertyAccessException` = `je#system_server` 整机重启。
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
     * 与 [XposedInit.handleLoadPackage] 同一套分发规则，去掉了 system_server 分支
     * （现代 API 下走 [onSystemServerStarting]）。
     */
    private fun hooksFor(target: HookTarget): Array<BaseHook>? {
        val appInfo = target.appInfo
        return when (target.packageName) {
            "com.google.android.projection.gearhead" -> arrayOf(AndroidAuoHook)
            "com.waze" -> arrayOf(OtherHook, WazeHook)
            // 只挂 GboardImeHook：OtherHook 会把 status_bar_height 归零，
            // 那是给投屏里的普通应用用的，输入法进程不需要也不该被改。
            GboardImeHook.PACKAGE_GBOARD -> arrayOf(GboardImeHook)
            // 模块自身进程不分发：LSPosed 不允许把模块自己选进作用域，
            // 这里永远不会被调到（框架也不会把模块加载进 io.github.nitsuya.aa.display）。
            BuildConfig.APPLICATION_ID -> null
            else -> if (appInfo == null || appInfo.uid == 1000) null else arrayOf(OtherHook)
        }
    }

    private fun initHooks(target: HookTarget, vararg hook: BaseHook) {
        // EzXHelperInit.initHandleLoadPackage 的字节码只做这两件事，这里等价替换，
        // 免得为了调它去伪造一个包私有构造器的 LoadPackageParam。
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
