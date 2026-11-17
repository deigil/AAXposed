package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.IsSystemEnv
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"
    override fun init(target: HookTarget) {
        Initiator.init(target.classLoader)
        log(tagName, "xposed init")
        var serviceManagerHook: XC_MethodHook.Unhook? = null
        serviceManagerHook = findMethod("android.os.ServiceManager") {
            name == "addService"
        }.hookBefore { param ->
            if (param.args[0] == "package") {
                serviceManagerHook?.unhook()
                val pms = param.args[1] as IPackageManager
                log(tagName, "Got pms: $pms")
                runCatching {
                    BridgeService.register(pms)
                    log(tagName, "Bridge service injected")
                }.onFailure {
                    log(tagName, "System service crashed", it)
                }
            }
        }

        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook = findAllConstructors("com.android.server.am.ActivityManagerService") {
            parameterTypes[0] == Context::class.java
        }.hookAfter {
            activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
            CoreManagerService.systemContext = it.thisObject.getObjectAs("mUiContext")
            log(tagName, "get systemUiContext")
        }.also {
            if (it.isEmpty())
                log(tagName, "no constructor with parameterTypes[0] == Context found")
        }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook = findMethod("com.android.server.am.ActivityManagerService") {
            name == "systemReady"
        }.hookAfter {
            activityManagerServiceSystemReadyHook?.unhook()
            CoreManagerService.systemReady()
            log(tagName, "system ready")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//10+
            var className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)  //12+
                "com.android.server.wm.ActivityTaskSupervisor"
            else  //10+
                "com.android.server.wm.ActivityStackSupervisor"
            findMethod(className){
                name == "isCallerAllowedToLaunchOnDisplay"
                && parameterCount == 4
                && parameterTypes[0] == Int::class.javaPrimitiveType //callingPid
                && parameterTypes[1] == Int::class.javaPrimitiveType //callingUid
                && parameterTypes[2] == Int::class.javaPrimitiveType //launchDisplayId
                && parameterTypes[3] == ActivityInfo::class.java
            }.hookAfter { param ->
                if((param.result as Boolean).not() && param.args[2] == CoreManagerService.getDisplayId()){
                    param.result = true
                    log(tagName,"hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        }

    }

    object Power {
        /**
         * HyperOS 3 moved short-press dispatch into its Miui shortcut rule.  It receives the
         * final press count, and forwarding it to OriginalPowerKeyRuleBridge locks the device.
         */
        private val miuiPowerPresses by lazy {
            if (!IsSystemEnv) return@lazy emptyList()
            runCatching {
                findAllMethods(
                    loadClass("com.android.server.input.shortcut.singlekeyrule.PowerKeyRule"),
                ) {
                        name == "onMiuiPress" &&
                            parameterTypes.contentEquals(
                                arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                            )
                }
                    .also { methods ->
                        if (methods.isNotEmpty()) {
                            log(tagName, "Power: matched ${methods.size} HyperOS power handler(s)")
                        }
                    }
            }.getOrElse { error ->
                log(tagName, "Power HyperOS handler lookup", error)
                emptyList()
            }
        }

        private val powerPresses by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                val policyClass = loadClass("com.android.server.policy.PhoneWindowManager")
                generateSequence(policyClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter {
                        if (it.name != "powerPress") return@filter false
                        val types = it.parameterTypes
                        types.size == 3
                            && types[0] == Long::class.javaPrimitiveType
                            && types[1] == Int::class.javaPrimitiveType
                            // Android 12-15: (long, int, boolean); Android 16: (long, int, int).
                            && (types[2] == Boolean::class.javaPrimitiveType
                                || types[2] == Int::class.javaPrimitiveType)
                    }
                    .distinctBy { it.declaringClass.name + it.toGenericString() }
                    .toList()
                    .also { methods ->
                        log(tagName, "Power: matched ${methods.size} powerPress handler(s): " +
                            methods.joinToString { it.declaringClass.name + "#" + it.name })
                    }
                    .takeIf { it.isNotEmpty() }
            } catch (e: Throwable){
                log(tagName,  "Power PhoneWindowManager.powerPress", e)
                null
            }
        }
        private var hookPower : List<XC_MethodHook.Unhook> = emptyList()
        fun hook(){
            unHook()
            hookPower = miuiPowerPresses.map { method -> method.hookBefore { param ->
                val count = param.args[1] as Int
                if (count == 1) {
                    log(tagName, "Power: intercepted HyperOS short press")
                    CoreApi.toggleDisplayPower()
                    param.abortMethod()
                }
            } }.ifEmpty {
                powerPresses?.map { method -> method.hookBefore {
                    if (method.parameterTypes[2] == Int::class.javaPrimitiveType) {
                        log(tagName, "Power: intercepted Android 16 power press")
                        CoreApi.toggleDisplayPower()
                        it.abortMethod()
                    } else if (!(it.args[2] as Boolean)) {
                        log(tagName, "Power: intercepted interactive power press")
                        CoreApi.toggleDisplayPower()
                        it.abortMethod()
                    } else {
                        log(tagName, "Power: waking display from non-interactive power press")
                        CoreApi.displayPower(true)
                    }
                } } ?: emptyList()
            }
            if (hookPower.isEmpty()) {
                log(tagName, "Power: no powerPress hook installed")
            } else {
                log(tagName, "Power: installed ${hookPower.size} powerPress hook(s)")
            }
        }
        fun unHook(){
            hookPower.forEach { it.unhook() }
            hookPower = emptyList()
        }
    }
    object FuckAppUseApplicationContext {
        private val appInitUseDisplay: HashMap<String, Int> = hashMapOf()
        private val appInitUseDisplayUid: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        private val activityTaskManagerService_startProcessAsync by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("com.android.server.wm.ActivityTaskManagerService"){
                    name == "startProcessAsync"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method", e)
                null
            }
        }
        private val applicationThread_bindApplication by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("android.app.IApplicationThread\$Stub\$Proxy"){
                    name == "bindApplication"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext IApplicationThread.bindApplication method", e)
                null
            }
        }
        private val displayManagerService_getDisplayInfo by lazy {
            if(!IsSystemEnv) return@lazy null
            try {
                findMethod("com.android.server.display.DisplayManagerService\$BinderService") {
                    name == "getDisplayInfo" && parameterCount == 1 && parameterTypes[0] == Int::class.javaPrimitiveType
                }
            } catch (e: Throwable) {
                log(tagName, "FuckAppUseAppContext DisplayManagerService.getDisplayInfo method", e)
                null
            }
        }
        private val windowManagerService_attachWindowContextToDisplayArea by lazy {
            if(!IsSystemEnv) return@lazy emptyList()
            try {
                findAllMethods("com.android.server.wm.WindowManagerService") {
                    name == "attachWindowContextToDisplayArea"
                }
            } catch (e: Throwable) {
                log(tagName, "FuckAppUseAppContext WindowManagerService.attachWindowContextToDisplayArea method", e)
                emptyList()
            }
        }

        private var activityTaskManagerService_startProcessAsync_hook : XC_MethodHook.Unhook? = null
        private var applicationThread_bindApplication_hook : XC_MethodHook.Unhook? = null
        private var displayManagerService_getDisplayInfo_hook : XC_MethodHook.Unhook? = null
        private var windowManagerService_attachWindowContextToDisplayArea_hooks : List<XC_MethodHook.Unhook> = emptyList()

        private fun shouldRedirectDefaultDisplay(callingUid: Int): Boolean {
            if (callingUid <= 10000) return false
            if (CoreManagerService.getDisplayId() == Display.INVALID_DISPLAY) return false
            return callingUid == CoreManagerService.getLauncherUid() || appInitUseDisplayUid.contains(callingUid)
        }

        fun hook(){
            unHook()
            activityTaskManagerService_startProcessAsync_hook  = activityTaskManagerService_startProcessAsync?.hookBefore { param ->
                try {
                    val activityRecord = param.args[0]
                    val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                    val packageName = activityRecord.getObject("packageName") as String
                    val uid = runCatching { activityRecord.invokeMethod("getUid") as? Int }.getOrNull()
                    if(displayId == 0){
                        if(appInitUseDisplay.containsKey(packageName)){
                            appInitUseDisplay.remove(packageName)
                        }
                        if (uid != null && uid != CoreManagerService.getLauncherUid()) {
                            appInitUseDisplayUid.remove(uid)
                        }
                        return@hookBefore
                    }
                    appInitUseDisplay[packageName] = displayId
                    if (uid != null) {
                        appInitUseDisplayUid.add(uid)
                    }
                } catch (e: Exception) {
                    log(tagName, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                }
            }
            applicationThread_bindApplication_hook = applicationThread_bindApplication?.hookBefore { param ->
                try {
                    val configuration = param.args.firstOrNull { it is Configuration } as? Configuration
                        ?: return@hookBefore
                    val packageName = (param.args[0] as String).run {
                        this.substringBeforeLast(":")
                    }
                    if(appInitUseDisplay.containsKey(packageName) || packageName == CoreManagerService.getLauncherPackage()){
                        val densityDpi = CoreManagerService.getDensityDpi()
                        if(densityDpi > 0){
                            configuration.densityDpi = densityDpi
                        }
                        val width = CoreManagerService.getDisplayWidth()
                        val height = CoreManagerService.getDisplayHeight()
                        if (width > 0 && height > 0 && densityDpi > 0) {
                            val density = densityDpi / 160f
                            val wDp = (width / density).toInt().coerceAtLeast(1)
                            val hDp = (height / density).toInt().coerceAtLeast(1)
                            configuration.orientation = if (width >= height) {
                                Configuration.ORIENTATION_LANDSCAPE
                            } else {
                                Configuration.ORIENTATION_PORTRAIT
                            }
                            configuration.screenWidthDp = wDp
                            configuration.screenHeightDp = hDp
                            configuration.smallestScreenWidthDp = minOf(wDp, hDp)
                            val winCfg = configuration.getObjectOrNull("windowConfiguration")
                            if (winCfg != null) {
                                val bounds = Rect(0, 0, width, height)
                                runCatching { winCfg.invokeMethod("setBounds", args(bounds), argTypes(Rect::class.java)) }
                                runCatching { winCfg.invokeMethod("setAppBounds", args(bounds), argTypes(Rect::class.java)) }
                                runCatching { winCfg.invokeMethod("setMaxBounds", args(bounds), argTypes(Rect::class.java)) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log(tagName, "applicationThread_bindApplication Hook Exception", e)
                }
            }
            displayManagerService_getDisplayInfo_hook = displayManagerService_getDisplayInfo?.hookBefore { param ->
                val reqDisplayId = param.args[0] as? Int ?: return@hookBefore
                if (reqDisplayId != Display.DEFAULT_DISPLAY) return@hookBefore
                if (!shouldRedirectDefaultDisplay(Binder.getCallingUid())) return@hookBefore
                val targetDisplayId = CoreManagerService.getDisplayId()
                if (targetDisplayId != Display.INVALID_DISPLAY) {
                    param.args[0] = targetDisplayId
                }
            }
            windowManagerService_attachWindowContextToDisplayArea_hooks =
                windowManagerService_attachWindowContextToDisplayArea.mapNotNull { method ->
                    val paramTypes = method.parameterTypes
                    val displayIdIndex = paramTypes.indexOfLast { it == Int::class.javaPrimitiveType }
                    if (displayIdIndex < 0) return@mapNotNull null
                    method.hookBefore { param ->
                        val reqDisplayId = param.args.getOrNull(displayIdIndex) as? Int ?: return@hookBefore
                        if (reqDisplayId != Display.DEFAULT_DISPLAY) return@hookBefore
                        if (!shouldRedirectDefaultDisplay(Binder.getCallingUid())) return@hookBefore
                        val targetDisplayId = CoreManagerService.getDisplayId()
                        if (targetDisplayId != Display.INVALID_DISPLAY) {
                            param.args[displayIdIndex] = targetDisplayId
                        }
                    }
                }
        }

        fun unHook(){
            appInitUseDisplay.clear()
            appInitUseDisplayUid.clear()
            activityTaskManagerService_startProcessAsync_hook?.apply { unhook() }
            activityTaskManagerService_startProcessAsync_hook = null

            applicationThread_bindApplication_hook?.apply { unhook() }
            applicationThread_bindApplication_hook = null

            displayManagerService_getDisplayInfo_hook?.apply { unhook() }
            displayManagerService_getDisplayInfo_hook = null

            windowManagerService_attachWindowContextToDisplayArea_hooks.forEach { it.unhook() }
            windowManagerService_attachWindowContextToDisplayArea_hooks = emptyList()
        }

    }

    /**
     * Make apps on the projection virtual display fill the entire screen.
     *
     * When portrait-only apps (WeChat `SCREEN_ORIENTATION_PORTRAIT`, Bilibili, etc.) launch on the
     * landscape VirtualDisplay, WM's fixed-orientation letterbox squeezes them into a centered 590x720
     * strip (`bounds=Rect(345, 0 - 935, 720)`, `letterBoxed=true`) with black bars on both sides.
     *
     * Tested findings (1280x720/160 overlay A/B):
     * - `IWindowManager.setIgnoreOrientationRequest(displayId, true/false)` has no effect on virtual
     *   displays; both values produce the same 590x720 bounds (virtual displays default to true).
     * - Stripping the activity's override orientation (compat `OVERRIDE_ANY_ORIENTATION_TO_USER`)
     *   immediately expands bounds to `Rect(0, 0 - 1280, 720)`, `letterBoxed=false`.
     *
     * Therefore, we override [ActivityRecord.getOverrideOrientation] to return
     * `SCREEN_ORIENTATION_UNSPECIFIED` on the projection display, letting the display's own
     * orientation drive layout without writing compat overrides or touching `getRequestedOrientation()`.
     * Restores automatically on unhook when projection disconnects.
     *
     * Matches purely by [CoreManagerService.getDisplayId], independent of resolution
     * (1280x720/160 DHU and 800x533/170 head unit behave identically).
     */
    object AppFullscreenOnProjection {
        private const val SCREEN_ORIENTATION_UNSPECIFIED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        private val activityRecordOrientationMethods: List<Method> by lazy {
            if (!IsSystemEnv) return@lazy emptyList()
            try {
                findAllMethods("com.android.server.wm.ActivityRecord", findSuper = true) {
                    (name == "getOverrideOrientation" && parameterCount == 0) ||
                        (name == "getOrientation" &&
                            (parameterCount == 0 ||
                                (parameterCount == 1 && parameterTypes[0] == Int::class.javaPrimitiveType)))
                }
            } catch (e: Throwable) {
                log(tagName, "AppFullscreen ActivityRecord orientation methods", e)
                emptyList()
            }
        }

        private var hookOrientations: List<XC_MethodHook.Unhook> = emptyList()

        private fun isOnProjectionDisplay(activityRecord: Any?): Boolean {
            activityRecord ?: return false
            val projectionDisplayId = CoreManagerService.getDisplayId()
            if (projectionDisplayId == Display.INVALID_DISPLAY) return false
            return try {
                activityRecord.invokeMethod("getDisplayId") as? Int == projectionDisplayId
            } catch (e: Throwable) {
                false
            }
        }

        fun hook() {
            unHook()
            val targets = activityRecordOrientationMethods
            if (targets.isEmpty()) {
                log(tagName, "AppFullscreen: no orientation method found, skip")
                return
            }
            hookOrientations = targets.mapNotNull { target ->
                runCatching {
                    target.hookAfter { param ->
                        try {
                            if ((param.result as? Int) == SCREEN_ORIENTATION_UNSPECIFIED) return@hookAfter
                            if (!isOnProjectionDisplay(param.thisObject)) return@hookAfter
                            param.result = SCREEN_ORIENTATION_UNSPECIFIED
                        } catch (e: Throwable) {
                            log(tagName, "AppFullscreen orientation Hook Exception", e)
                        }
                    }
                }.getOrNull()
            }
            log(
                tagName,
                "AppFullscreen hooked ${hookOrientations.size} method(s): " +
                    targets.joinToString { "${it.declaringClass.simpleName}.${it.name}(p${it.parameterCount})" }
            )
        }

        fun unHook() {
            hookOrientations.forEach { it.unhook() }
            hookOrientations = emptyList()
        }
    }
}
