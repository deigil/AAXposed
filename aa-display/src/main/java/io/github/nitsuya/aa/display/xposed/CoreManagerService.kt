package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.*
import android.view.*
import com.github.kyuubiran.ezxhelper.utils.*
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.ui.window.DisplayWindow
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.util.EyeCareSuspender
import io.github.nitsuya.aa.display.util.ProjectionState
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CoreManagerService private constructor(): ICoreManager.Stub() {
    companion object {
        const val TAG = "CoreManagerService"

        val instance: CoreManagerService by lazy {
            CoreManagerService().apply {
                log(TAG, "AADisplay service initialized")
            }
        }

        @SuppressLint("StaticFieldLeak")
        private lateinit var systemContextHost: Context
        var systemContext: Context
            get() = systemContextHost
            set(value) {
                log(TAG, "SystemContext.params is null: ${value.params}")
                systemContextHost = value.createContext(value.params ?: ContextParams.Builder().build())
            }

        /**
         * system_server 侧的模块配置。来源由入口类注入（现代 API 走远端配置，
         * 旧框架走 XSharedPreferences），这里只负责取。
         */
        val config: SharedPreferences? get() = HookPreferences.get()

        private var mDisplayWindow: DisplayWindow? = null
        private var mAaVirtualDisplayAdapter: AaVirtualDisplayAdapter? = null
        private var mDisplayCreateInProgress = false
        private data class DisplayProfile(
            val width: Int,
            val height: Int,
            val densityDpi: Int
        ) {
            val isLandscape: Boolean
                get() = width >= height
        }
        private var mLockedDisplayProfile: DisplayProfile? = null

        /** true once [systemContext] has been assigned (Coolwalk settle needs a real Context). */
        private val hasSystemContext: Boolean
            get() = ::systemContextHost.isInitialized

        private const val RAIL_SETTLE_RETRY_MS = 450L
        private val mMainHandler = Handler(Looper.getMainLooper())
        private val mRailSettleRetryRunnable = Runnable { retryRailAwareSettle() }

        @Volatile
        private var mCoolwalkReconnectEpochMs = 0L

        private fun cancelRailSettleRetry() {
            mMainHandler.removeCallbacks(mRailSettleRetryRunnable)
        }

        private fun scheduleRailSettleRetry() {
            cancelRailSettleRetry()
            mMainHandler.postDelayed(mRailSettleRetryRunnable, RAIL_SETTLE_RETRY_MS)
        }

        /**
         * After soft reconnect, GhFacetBar may appear (or be starved) a beat after the
         * first create. Re-settle so we neither stay at full HU with a live rail strip
         * nor stay at HU−rail after the strip is gone.
         */
        private fun retryRailAwareSettle() {
            val current = mLockedDisplayProfile ?: return
            val adapter = mAaVirtualDisplayAdapter ?: return
            if (!hasSystemContext) return
            val railW = resolveRailWidthPx()
            val fullW = resolveObservedFullHuWidthPx(current.width, current.height)
            val settled = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(current.width, current.height, current.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val next = sanitizeDisplayProfile(settled.width, settled.height, settled.densityDpi)
            if (next == current) return
            mLockedDisplayProfile = next
            log(
                TAG,
                "displayProfile relocked(rail-settle): ${current.width}*${current.height} -> " +
                    "${next.width}*${next.height} rail=$railW full=$fullW"
            )
            if (next.width > current.width &&
                DisplayProfileSettle.isContentSlotVsFull(current.width, next.width)
            ) {
                instance.notifyCoolwalkFullBleed()
            }
            runMain {
                adapter.onReconnected(next.width, next.height, next.densityDpi)
                mDisplayWindow?.onResume(next.width, next.height)
            }
        }

        /**
         * Compositor FacetBar strip is the only profile input — never touchRailWidthPx
         * or a cached FullBleed phase (stale after reconnect blacks the left gutter).
         */
        private fun resolveRailWidthPx(): Int {
            if (!hasSystemContext) return 0
            val liveRail = DisplayProfileSettle.observeLiveRailWidthPx(systemContext)
            return if (liveRail <= 1) 0 else liveRail
        }

        private fun resolveObservedFullHuWidthPx(reportedWidth: Int, reportedHeight: Int): Int {
            if (!hasSystemContext) return reportedWidth
            val snap = CoolwalkRailStore.effectiveSnapshot(systemContext.contentResolver)
            val observed = DisplayProfileSettle.observeFullHuWidthPx(
                systemContext,
                reportedWidth.coerceAtLeast(1),
                reportedHeight,
            )
            val measured = observed.coerceAtLeast(reportedWidth)
            return CoolwalkRailMath.pickConnectionFullHuWidth(
                snap.fullHuWidthPx,
                snap.layoutHeightPx,
                measured,
                reportedHeight,
                snap.touchRailWidthPx,
            )
        }

        private fun sanitizeDisplayProfile(width: Int, height: Int, densityDpi: Int): DisplayProfile {
            return DisplayProfile(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(1)
            )
        }

        /**
         * Lock profile per active AA display session.
         * - New display session: (re)learn from current size.
         * - Reconnect within same session: keep locked size unless orientation flips,
         *   or Coolwalk reclaimed the vertical rail (then widen to the full HU).
         */
        private fun resolveDisplayProfile(
            width: Int,
            height: Int,
            densityDpi: Int,
            newSession: Boolean
        ): DisplayProfile {
            val reported = sanitizeDisplayProfile(width, height, densityDpi)
            val railW = resolveRailWidthPx()
            val fullW = resolveObservedFullHuWidthPx(reported.width, reported.height)
            val settled = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(reported.width, reported.height, reported.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val candidate = sanitizeDisplayProfile(settled.width, settled.height, settled.densityDpi)
            val current = mLockedDisplayProfile
            if (current == null) {
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile locked: ${candidate.width}*${candidate.height},${candidate.densityDpi}" +
                        " (reported=${reported.width} rail=$railW full=$fullW)"
                )
                if (!newSession) scheduleRailSettleRetry()
                return candidate
            }
            if (newSession) {
                cancelRailSettleRetry()
                if (current != candidate) {
                    mLockedDisplayProfile = candidate
                    log(
                        TAG,
                        "displayProfile relocked(new-session): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                    )
                }
                return mLockedDisplayProfile!!
            }
            if (current.isLandscape != candidate.isLandscape) {
                cancelRailSettleRetry()
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile relocked(orientation): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
                return candidate
            }
            // Only Coolwalk rail reclaim may widen a locked profile mid-session; every other
            // reconnect jitter keeps the locked size exactly as before.
            if (candidate.width > current.width &&
                candidate.height == current.height &&
                DisplayProfileSettle.isContentSlotVsFull(current.width, candidate.width)
            ) {
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile relocked(coolwalk-settle): ${current.width}*${current.height} -> " +
                        "${candidate.width}*${candidate.height} reported=${reported.width} rail=$railW full=$fullW"
                )
                instance.notifyCoolwalkFullBleed()
                scheduleRailSettleRetry()
                return candidate
            }
            if (current != candidate) {
                log(
                    TAG,
                    "displayProfile keep-locked(reconnect): locked=${current.width}*${current.height},${current.densityDpi}, incoming=${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
            }
            // Rail / starve often lands after the first soft-reconnect create; one retry.
            scheduleRailSettleRetry()
            return current
        }

        private fun clearDisplayProfileLock() {
            cancelRailSettleRetry()
            CoolwalkRailStore.clear(if (hasSystemContext) systemContext.contentResolver else null)
            mLockedDisplayProfile?.also {
                log(TAG, "displayProfile cleared: ${it.width}*${it.height},${it.densityDpi}")
            }
            mLockedDisplayProfile = null
        }

        /**
         * MIUI 护眼矩阵是全局的（display 0 → SF 广播全屏），投屏期间抑制、断开后还原。
         * 软重连会重复进 [onCreateDisplay]，[EyeCareSuspender.suspend] 自身幂等。
         */
        private fun suspendEyeCareForProjection() {
            if (!hasSystemContext) return
            val enabled = AADisplayConfig.SuspendEyeCareOnProjection.get(config)
            val r = EyeCareSuspender.suspend(systemContext, enabled)
            if (r is EyeCareSuspender.Result.Failed) {
                log(TAG, "eyeCare suspend failed", r.error)
            } else if (r !is EyeCareSuspender.Result.Disabled &&
                r !is EyeCareSuspender.Result.NothingToDo
            ) {
                log(TAG, "eyeCare suspend: $r")
            }
        }

        private fun restoreEyeCareAfterProjection() {
            if (!hasSystemContext) return
            val r = EyeCareSuspender.restore(systemContext)
            if (r is EyeCareSuspender.Result.Failed) {
                log(TAG, "eyeCare restore failed", r.error)
            } else if (r !is EyeCareSuspender.Result.NotActive) {
                log(TAG, "eyeCare restore: $r")
            }
        }

        /**
         * 投屏状态位，供普通应用进程里的 hook 读取
         * （[io.github.nitsuya.aa.display.xposed.hook.GboardImeHook]）。
         * 与护眼抑制同一对生命周期点，软重连重复调用保持幂等。
         */
        private fun markProjectionState(active: Boolean) {
            if (!hasSystemContext) return
            val cr = systemContext.contentResolver
            val ok = if (active) ProjectionState.markActive(cr) else ProjectionState.markInactive(cr)
            if (!ok) log(TAG, "projection state write failed: active=$active")
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun systemReady() {
//            systemContext.registerReceiver(CoreBroadcastReceiver, IntentFilter().apply {
//                addAction("com.google.android.gearhead.ASSISTANT_STATE_CHANGED")
//                addAction("com.google.android.apps.auto.carservice.service.impl.NEARBY_ACTION_STOP_PROJECTION")
//                addAction("com.google.android.gms.car.STARTUP_LATENCY_MEASUREMENT_EVENT")
//                addAction("com.google.android.gms.car.FRX")
//                addAction("com.google.android.gms.car.DISCONNECTED")
//                addAction("com.google.android.gms.car.BIND_CAR_INPUT")
//                addAction("com.google.android.gms.car.PROJECTION_STARTED")
//                addAction("com.google.android.gms.car.PROJECTION_ENDED")
//                addAction("com.google.android.gms.car.FIRST_ACTIVITY")
//            })

            TipUtil.init(systemContext, "[AADisplay] ")
            Instances.init(systemContext)

            // 提前把 EzXHelper 的 modulePath 解析好。悬浮窗（CommonContextWrapper）要用它，
            // 而那条路径跑在 system_server 主 looper 上、只在插车时才走到 —— 放到这里做，
            // 失败会在开机日志里直接暴露，而不是等到投屏那一刻。
            log(TAG, "module assets ready: ${ModuleAssets.ensureModulePath()}")

            // 上一次开机挂起后未能还原（进程被杀 / 直接重启）时，screen_paper_mode_enabled 会带着
            // 我们置的值持久化下来使护眼一直失效，这里兜底回滚。
            EyeCareSuspender.recoverStaleSuspend(systemContext).also { r ->
                if (r !is EyeCareSuspender.Result.NotActive) {
                    log(TAG, "eyeCare stale recover: $r")
                }
            }

            // 同理清掉可能残留的投屏状态位，避免第三方进程一直按“投屏中”行事。
            markProjectionState(false)

//            runIO {
//                try{
//                    com.google.android.gms.car.a::class.java.invokeStaticMethod("b", args(systemContext), argTypes(Context::class.java))
//                    val classDynamicApiFactory = com.google.android.gms.car.a::class.java.field("a", true, Class::class.java).get(null)
//                    val instance = PathClassLoader(
//                        "/data/local/car_sdk_impl/sdk_impl.jar",
//                        CarSdkClassLoader(this.javaClass.classLoader)
//                    )
//                    instance.loadClass("com.google.android.gms.car.internal.GmsVersionChecker").invokeStaticMethod("disableGmsVersionChecks")
//                    instance.loadClass("com.google.android.gms.car.CarVersionUtils").invokeStaticMethod("versionCheck")
//                    classDynamicApiFactory.field("instance", true, ClassLoader::class.java).set(null, instance)
//                    val car = Car.createCar(systemContext, object: CarConnectionCallback(){
//                        override fun onConnected(car: Car) {
//                            log(TAG, "AA已连接")
//                            val carManager = car.getCarManager("car_1p")
//                            Companion.instance?.showToast("AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}")
//                            log(TAG, "AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}") //CarFirstPartyManager
//                            Companion.instance?.showToast("AA已连接")
//                        }
//                        override fun onDisconnected(car: Car) {
//                            log(TAG, "AA已断开")
//                            Companion.instance?.showToast("AA 已断开")
//                        }
//                    }).apply {
//                        connect()
//                        log(TAG, "AA初始化完成")
//                        Companion.instance?.showToast("AA初始化完成")
//                    }
//                    if(car == null){
//                        Companion.instance?.showToast("AA初始化失败")
//                        log(TAG, "AA初始化失败")
//                    }
//                } catch (e: Throwable){
//                    Companion.instance?.showToast("AA初始化异常:" + e.message)
//                    log(TAG, "AA初始化异常", e)
//                }
//            }
        }

        fun getDisplayId(): Int{
            return mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY
        }

        fun getDensityDpi(): Int{
            return mAaVirtualDisplayAdapter?.mDensityDpi ?: 0
        }
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun onCreateDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?, listener: IVirtualDisplayCreatedListener){
        runMain {
            log(
                TAG,
                "onCreateDisplay request: ${width}x$height,$densityDpi surface=${surface != null} existing=${mAaVirtualDisplayAdapter != null}"
            )
            val profile = resolveDisplayProfile(
                width = width,
                height = height,
                densityDpi = densityDpi,
                newSession = mAaVirtualDisplayAdapter == null
            )
            mAaVirtualDisplayAdapter?.apply {
                onReconnected(profile.width, profile.height, profile.densityDpi)
                setSurface(surface)
                mDisplayWindow?.onResume(profile.width, profile.height)
                suspendEyeCareForProjection()
                markProjectionState(true)
                listener.onAvailableDisplay(this.mDisplayId, false)
                return@runMain
            }
            if (mDisplayCreateInProgress) {
                log(
                    TAG,
                    "onCreateDisplay ignored: display create already in progress for ${profile.width}x${profile.height},${profile.densityDpi}"
                )
                return@runMain
            }
            val config = HookPreferences.latest()
            config?.apply {
                log(TAG, "config: ${this.all.map { "${it.key}=${it.value}[${it.value?.javaClass?.name}]" }.joinToString() }")
            }
            mDisplayCreateInProgress = true
            suspendEyeCareForProjection()
            markProjectionState(true)
            AaVirtualDisplayAdapter(systemContext, config){
                try {
                    mAaVirtualDisplayAdapter = this
                    onConnected(profile.width, profile.height, profile.densityDpi, surface){ displayId ->
                        listener.onAvailableDisplay(displayId, true)
                    }
                    mDisplayWindow?.onDestroyPromptly()
                    mDisplayWindow = DisplayWindow(
                        CommonContextWrapper.createAppCompatContext(systemContext),
                        this,
                        profile.width,
                        profile.height,
                        profile.densityDpi
                    )
                } catch (t: Throwable) {
                    // 这里跑在 system_server 主 looper 上（runMain + Dispatchers.Main），
                    // 且 GlobalScope 没有 CoroutineExceptionHandler：异常逃出去会走到线程的
                    // 默认处理器，直接把 system_server 打死 → 整机软重启（je#system_server）。
                    // 投屏画面本身由 VirtualDisplay 承载，悬浮窗建失败也不该拖垮系统。
                    log(TAG, "onCreateDisplay failed after virtual display setup", t)
                } finally {
                    mDisplayCreateInProgress = false
                }
            }
        }
    }

    override fun setDisplaySurface(surface: Surface?){
        runMain {
            log(TAG, "setDisplaySurface: surface=${surface != null}, display=${mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY}")
            mAaVirtualDisplayAdapter?.setSurface(surface)
        }
    }

    override fun onDestroyDisplay(){
        runMain {
            val window = mDisplayWindow
            if (window == null) {
                // 悬浮窗还没建起来就断开：延迟销毁回调不会触发，护眼必须在这里还原。
                restoreEyeCareAfterProjection()
                markProjectionState(false)
                return@runMain
            }
            window.onDestroy {
                mAaVirtualDisplayAdapter?.onDestroy()
                mDisplayWindow = null
                mAaVirtualDisplayAdapter = null
                mDisplayCreateInProgress = false
                clearDisplayProfileLock()
                restoreEyeCareAfterProjection()
                markProjectionState(false)
            }
        }
    }

    override fun startLauncher() {
        runIO {
            mAaVirtualDisplayAdapter?.run {
                startLauncher()
            }
        }
    }

    override fun startActivity(packageName: String, userId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.run {
                startActivity(packageName, userId)
            }
        }
    }

    override fun startTaskId(taskId: Int, packageName: String, userId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.startTaskId(taskId, packageName, userId)
        }
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        runIO {
            mAaVirtualDisplayAdapter?.moveTaskId(taskId, isVirtualDisplay)
        }
    }

    override fun moveTaskToFront(taskId: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.moveTaskToFront(taskId)
        }
    }

    override fun moveSecondTaskToFront() {
        runIO {
            mAaVirtualDisplayAdapter?.moveSecondTaskToFront()
        }
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int){
        runIO {
            mAaVirtualDisplayAdapter?.removeTask(taskId)
        }
    }

    override fun pressKey(action: Int) {
        runIO {
            mAaVirtualDisplayAdapter?.onPressKey(action)
        }
    }

    override fun touch(event: MotionEvent) {
        runBlocking(Dispatchers.IO) {
//        runMain {
            mAaVirtualDisplayAdapter?.onTouch(event)
        }
    }

//    private var repairDownTime: Long = Long.MIN_VALUE
//    fun touch2(event: MotionEvent, ratio: Float, repairDownTime: Long) {
//        mAaVirtualDisplayAdapter?.run {
//            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
//            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
//            val oldCoords = MotionEvent.PointerCoords()
//            for (i in 0 until event.pointerCount) {
//                val pointerProperty = MotionEvent.PointerProperties()
//                event.getPointerCoords(i, oldCoords)
//                event.getPointerProperties(i, pointerProperty)
//                pointerCoords[i] = MotionEvent.PointerCoords()
//                pointerCoords[i]!!.apply {
//                    x = if(ratio == 1f) oldCoords.x else oldCoords.x / ratio
//                    y = if(ratio == 1f) oldCoords.y else oldCoords.y / ratio
//                }
//                pointerProperties[i] = pointerProperty
//            }
//
//            val newEvent = MotionEvent.obtain(
//                if(repairDownTime == Long.MIN_VALUE) event.downTime else repairDownTime,
//                if(repairDownTime == Long.MIN_VALUE) event.eventTime else SystemClock.uptimeMillis(),
//                event.action,
//                event.pointerCount,
//                pointerProperties,
//                pointerCoords,
//                event.metaState,
//                event.buttonState,
//                event.xPrecision,
//                event.yPrecision,
//                event.deviceId,
//                event.edgeFlags,
//                event.source,
//                event.flags
//            )
//            onTouch(event)
//            newEvent.recycle()
//        }
//    }

    override fun toggleDisplayPower() {
        runIO {
            mDisplayWindow?.toggleDisplayPower()
        }
    }

    override fun displayPower(displayPower: Boolean) {
        runIO {
            mDisplayWindow?.toggleDisplayPower(displayPower)
        }
    }

    override fun addMirror(surfaceControl: SurfaceControl) {
        runIO {
            mAaVirtualDisplayAdapter?.addMirror(surfaceControl)
        }
    }

    override fun removeMirror(surfaceControl: SurfaceControl){
        runIO {
            mAaVirtualDisplayAdapter?.removeMirror(surfaceControl)
        }
    }

    override fun getRecentTask(): RecentTask {
        return runBlocking(Dispatchers.IO){
            mAaVirtualDisplayAdapter?.getRecentTask() ?: RecentTask(emptyList(), emptyList())
        }
    }

    @SuppressLint("RestrictedApi")
    override fun testCode(action: String){
//        runMain {
//            try {
//                when (action) {
//                    "displayPowerMode" -> {
//                        try{
//                            val internalDisplayToken = SurfaceControlHidden.getInternalDisplayToken()
//                            SurfaceControlHidden.setDisplayPowerMode(internalDisplayToken, 0) // POWER_MODE_OFF-0  POWER_MODE_NORMAL-2
//                        } catch (e: Throwable){
//                            log(TAG, "DisplayToken", e)
//                        }
//                    }
//                    "aaSdk" -> {
//                        try{
//                            com.google.android.gms.car.a::class.java.invokeStaticMethod("b", args(systemContext), argTypes(Context::class.java))
//                            val classDynamicApiFactory = com.google.android.gms.car.a::class.java.field("a", true, Class::class.java).get(null)
//                            val instance = PathClassLoader(
//                                "/data/local/car_sdk_impl/sdk_impl.jar",
//                                CarSdkClassLoader(this.javaClass.classLoader)
//                            )
//                            classDynamicApiFactory.field("instance", true, ClassLoader::class.java).set(null, instance)
//                            instance.loadClass("com.google.android.gms.car.internal.GmsVersionChecker").invokeStaticMethod("disableGmsVersionChecks")
//                            instance.loadClass("com.google.android.gms.car.CarVersionUtils").invokeStaticMethod("versionCheck")
//                            val car = Car.createCar(systemContext, object: CarConnectionCallback(){
//                                override fun onConnected(car: Car) {
//                                    log(TAG, "AA已连接")
//                                    TipUtil.showToast("AA已连接")
////                                    val carManager = car.getCarManager("car_1p")
////                                    showToast("AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}")
////                                    log(TAG, "AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}") //CarFirstPartyManager
//                                }
//                                override fun onDisconnected(car: Car) {
//                                    log(TAG, "AA已断开")
//                                    TipUtil.showToast("AA 已断开")
//                                }
//                            }).apply {
//                                connect()
//                                log(TAG, "AA初始化完成")
//                                TipUtil.showToast("AA初始化完成")
//                            }
//                            if(car == null){
//                                TipUtil.showToast("AA初始化失败")
//                                log(TAG, "AA初始化失败")
//                            }
//                        } catch (e: Throwable){
//                            TipUtil.showToast("AA初始化异常:" + e.message)
//                            log(TAG, "AA初始化异常", e)
//                        }
//                    }
//                }
//            }catch (e: Throwable){
//                TipUtil.showToast("TestCode[$action]: ${e.message}")
//                log(TAG, "TestCode[$action]:", e)
//            }
//        }
    }

    override fun toast(msg: String){
        runMain {
            TipUtil.showToast(msg)
        }
    }

    override fun printLog(tag: String, msg: String){
        runIO {
            log(tag, msg)
        }
    }

    override fun reportCoolwalkRailSnapshot(
        phase: Int,
        touchRailWidthPx: Int,
        fullHuWidthPx: Int,
        facetDisplayId: Int,
    ) {
        if (phase == RailPhase.ReconnectSettling.code) {
            mCoolwalkReconnectEpochMs = SystemClock.uptimeMillis()
        }
        val snapshot = RailSnapshot(
            phase = RailPhase.fromCode(phase),
            effectiveRailWidthPx = 0,
            touchRailWidthPx = touchRailWidthPx,
            fullHuWidthPx = fullHuWidthPx,
            facetDisplayId = facetDisplayId,
            updatedUptimeMs = SystemClock.uptimeMillis(),
            lastEvent = "client-report",
        )
        val prevFull = CoolwalkRailStore.serverSnapshot.fullHuWidthPx
        val cr = if (hasSystemContext) systemContext.contentResolver else null
        CoolwalkRailStore.rememberFromReport(snapshot, cr)
        CoolwalkRailStore.publishServer(snapshot)
        if (cr != null) {
            CoolwalkRailStore.write(cr, snapshot)
        }
        log(
            TAG,
            "CoolwalkRail snapshot phase=${snapshot.phase} touch=$touchRailWidthPx full=$fullHuWidthPx facet=$facetDisplayId"
        )
        if (fullHuWidthPx > prevFull || snapshot.phase == RailPhase.FullBleed) {
            scheduleRailSettleRetry()
        }
    }

    override fun getCoolwalkRailSnapshot(): IntArray {
        val s = CoolwalkRailStore.effectiveSnapshot(
            if (hasSystemContext) systemContext.contentResolver else null,
        )
        return intArrayOf(s.phase.code, s.touchRailWidthPx, s.fullHuWidthPx, s.facetDisplayId)
    }

    override fun getCoolwalkReconnectEpochMs(): Long = mCoolwalkReconnectEpochMs

    /**
     * gearhead cannot reliably deliver package-targeted broadcasts on Android 13+, so the
     * full-bleed signal is relayed from the system uid instead.
     */
    override fun notifyCoolwalkFullBleed() {
        if (!hasSystemContext) return
        runIO {
            try {
                systemContext.sendBroadcast(
                    Intent(AABroadcastConst.ACTION_COOLWALK_FULL_BLEED)
                        .setPackage(BuildConfig.APPLICATION_ID)
                )
            } catch (e: Throwable) {
                log(TAG, "notifyCoolwalkFullBleed failed", e)
            }
        }
    }
}
