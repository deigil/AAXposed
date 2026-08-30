package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.WindowControllerBinding
import io.github.nitsuya.aa.display.databinding.WindowMirrorBinding
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class DisplayWindow(
      private val mContext: Context
    , private val displayAdapter: AaVirtualDisplayAdapter
    , private var mDisplayWidth: Int
    , private var mDisplayHeight: Int
    , private var mDensityDpi: Int
): View.OnTouchListener {
    companion object {
        private const val TAG = "AADisplay_DisplayWindow"
    }

    private var mControllerBinding: WindowControllerBinding? = null
    private lateinit var mControllerLayoutParams: WindowManager.LayoutParams

    private var mMirrorBinding: WindowMirrorBinding? = null
    private lateinit var mMirrorLayoutParams: WindowManager.LayoutParams

    private var mControllerStatus = false
    private var mMirrorStatus = false
    private var mControllerCollapsed = true
    private var mControllerDockRight = true
    private val mControllerPeekPx by lazy { (mContext.resources.displayMetrics.density * 14f).toInt() }

    /**
     * 镜像小窗的几何状态（MIUI 小窗逻辑：整体可拖动、可等比缩放）。
     *
     * mMirrorFitRatio 是“恰好铺满手机可用区”的缩放，mMirrorScale 是用户在这个基准上
     * 的比例（拖右下角把手改它），两者相乘才是 mDisplayRatio。触摸转发要除以
     * mDisplayRatio 把坐标换回车机分辨率，所以缩放必须只走 updateDipslaySize()
     * 这一条路径，否则画面和点击位置会错开。
     */
    private var mMirrorFitRatio = 1f
    private var mMirrorScale = 1f
    private var mMirrorPositioned = false
    private var mMirrorDragLastX = 0f
    private var mMirrorDragLastY = 0f
    private var mResizeStartRawX = 0f
    private var mResizeStartRawY = 0f
    private var mResizeStartScale = 1f
    private var mResizing = false

    private val mMirrorShadowPadPx by lazy {
        mContext.resources.getDimensionPixelSize(R.dimen.mirror_window_shadow_pad)
    }
    private val mMirrorMinWidthPx by lazy {
        mContext.resources.getDimensionPixelSize(R.dimen.mirror_min_width)
    }
    /** 多任务面板：单个图标条目的参考宽度 / RecyclerView 的 padding，用来算网格列数 */
    private val mMirrorRecentItemWidthPx by lazy {
        mContext.resources.getDimensionPixelSize(R.dimen.mirror_recent_item_width).coerceAtLeast(1)
    }
    private val mMirrorRecentPaddingPx by lazy {
        (mContext.resources.displayMetrics.density * 4f).toInt()
    }
    /** 顶栏 + 底部导航条占掉的高度，算可用空间时要从屏幕高度里扣掉 */
    private val mMirrorChromeHeightPx by lazy {
        mContext.resources.let {
            it.getDimensionPixelSize(R.dimen.mirror_top_bar_height) +
                it.getDimensionPixelSize(R.dimen.mirror_nav_btn_size) +
                (it.displayMetrics.density * 8f).toInt()
        }
    }

    /**
     * 悬浮窗必须落在手机主屏（display 0）上。
     *
     * mContext 最终来自 AMS 的 mUiContext，构造 DisplayWindow 的时机正好卡在
     * gearhead 换屏之后，它可能还绑在刚被移除的虚拟屏上，直接 addView 会抛
     * InvalidDisplayException（"Attempted to add window to a display that does
     * not exist"），首次 showController 就被 tryOrNull 吞掉。这里显式绑定默认屏，
     * 顺带让 currentWindowMetrics 也取到手机主屏的尺寸。
     */
    private val mWindowManager: WindowManager by lazy {
        val windowContext = tryOrNull {
            mContext.createDisplayContext(Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } ?: mContext
        windowContext.getSystemService(WindowManager::class.java)
    }

    private var mDisplayRatio = 1f
    private var mDisplayPower = true

    private var mDestroyJob: Job? = null
    private var mChangeAlphaCountDownTimer = object : CountDownTimer(5000,5000){
        override fun onFinish() {
            mControllerBinding?.apply {
                ViewCompat.animate(root).setDuration(500).alpha(0.5F).start()
            }
        }
        override fun onTick(millisUntilFinished: Long) {}
    }

    private var mScreenOffReplaceLockScreen = AADisplayConfig.ScreenOffReplaceLockScreen.get(CoreManagerService.config)
    private val mDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(CoreManagerService.config).let { value ->
        if (value < 0) 0 else value
    }

    private val isSupportInteractive = RomUtil.isMiui()
    private var interactiveMonitor = object: BroadcastReceiver(){
        val monitor by lazy {
            Instances.powerManagerHidden.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                , "${BuildConfig.APPLICATION_ID}:Monitor", displayAdapter.mVirtualDisplay.display.displayId).apply {
                    setReferenceCounted(false)
            }
        }
        fun addAction(intentFilter: IntentFilter): IntentFilter {
            return intentFilter.apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        }
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { action ->
                onReceive(context, action)
            }
        }
        fun onReceive(context: Context, action: String){
            if(!isSupportInteractive) return
            try {
                when(action){
                    Intent.ACTION_SCREEN_ON -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 0)
                    Intent.ACTION_SCREEN_OFF -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1)
                    //miui
                    //Settings.Global.putInt(contentResolver, "ucar_casting_state", 1);
                    //Settings.Secure.putInt(contentResolver, "screen_project_in_screening", 1);
                    //Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1);
                }
            } catch (e : Throwable){}
        }
        fun init(){
            if(mScreenOffReplaceLockScreen){
                AndroidHook.Power.hook()
            } else if(isSupportInteractive){
                mContext.registerReceiver(this, addAction(IntentFilter()))
                onReceive(mContext, if(Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
            } else {
                if (!monitor.isHeld) {
                    monitor.acquire()
                }
            }
            AndroidHook.FuckAppUseApplicationContext.hook()
        }
        fun release(){
            if(mScreenOffReplaceLockScreen){
                AndroidHook.Power.unHook()
            } else if(isSupportInteractive){
                mContext.unregisterReceiver(this)
                onReceive(mContext, Intent.ACTION_SCREEN_ON)
            } else {
                monitor.release()
            }
            AndroidHook.FuckAppUseApplicationContext.unHook()
        }
    }



    init {
        runCatching {
            with(ContextThemeWrapper(mContext, R.style.Theme_AADisplay_Window)){
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
                // 小窗版镜像窗不再铺满全屏，多任务面板直接叠在车机画面上，
                // 不需要再为状态栏留出 padding
                mMirrorBinding = WindowMirrorBinding.inflate(LayoutInflater.from(this))
            }
        }.onFailure {
            log(TAG, "init: new window failed may you forget reboot", it)
            TipUtil.showToast("new window failed\nmay you forget reboot")
        }.onSuccess {
            doInit()
        }
        interactiveMonitor.init()
    }

    fun doInit() {
        initLayoutParams()
        mControllerBinding?.apply {
            root.allViews.forEach {
                it.setOnTouchListener(this@DisplayWindow)
            }
            ibHandle.setOnClickListener {
                expandController()
            }
            ibHideController.setOnClickListener {
                collapseController()
            }
            if(mScreenOffReplaceLockScreen){
                ibExtinguish.visibility = View.VISIBLE
                ibExtinguish.setOnClickListener {
                    toggleDisplayPower(false)
                }
            }
            ibMirrorDisplay.setOnClickListener {
                hideController()
                showMirror()
            }
            ibMirrorDisplay.setOnLongClickListener {
                collapseController()
                true
            }
            ibExpand.setOnClickListener {
                expandController()
            }
        }
        mMirrorBinding?.apply {
            // 顶栏 / 底部导航条空白处拖动 = 移动整个小窗
            arrayOf(llMirrorTopBar, rlMirrorNav).forEach {
                it.setOnTouchListener { _, event -> onMirrorDragTouch(event) }
            }
            ibClose.setOnClickListener {
                hideMirror()
                showController()
            }
            ivResize.setOnTouchListener { v, event -> onMirrorResizeTouch(v, event) }
            ivResize.setOnLongClickListener {
                resetMirrorSizeAndPosition()
                TipUtil.showToast(mContext.getString(R.string.mirror_size_reset))
                true
            }
            ibBack.setOnClickListener {
                displayAdapter.onPressKey(KeyEvent.KEYCODE_BACK)
            }
            ibHome.setOnClickListener {
                displayAdapter.startLauncher()
            }
            ibRecentTask.setOnClickListener { v ->
                val tapCount = v.getTag(R.id.tap_count) as? Int ?: 0
                v.setTag(R.id.tap_count,   tapCount + 1)
                if(tapCount > 0) return@setOnClickListener
                runIO {
                    delay(300)
                    runMain {
                        when(v.getTag(R.id.tap_count) as? Int ?: 0){
                            1 -> toggleRecentTask()
                            2 -> displayAdapter.moveSecondTaskToFront()
                        }
                        v.setTag(R.id.tap_count,  0)
                    }
                }
            }
            tvVirtualDisplayInfo.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
            rvRecentTaskLeft.apply {
                layoutManager = GridLayoutManager(context, 1)
                adapter = DisplayRecyclerViewAdapter(this, iconMode = true) {
                    hideRecentTask()
                }
            }
            rvRecentTaskRight.apply {
                layoutManager = GridLayoutManager(context, 1)
                adapter = DisplayRecyclerViewAdapter(this, iconMode = true){
                    hideRecentTask()
                }.apply {
                    otherAdapter = (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter).also {
                        it.otherAdapter = this@apply
                    }
                }
            }
            arrayOf(rvRecentTaskLeft, rvRecentTaskRight).forEach {
                it.setOnTouchListener { v, event ->
                    when(event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.setTag(R.id.drag_last_x, event.x)
                            v.setTag(R.id.drag_last_y, event.y)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (v.id != 0
                                && abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                                && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5) {
                                hideRecentTask()
                            }
                        }
                    }
                    return@setOnTouchListener false
                }
            }
        }
        updateDipslaySize()
        mMirrorBinding?.apply {
            svMirror.setOnTouchListener { _, event ->
                val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
                val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
                val oldCoords = MotionEvent.PointerCoords()
                for (i in 0 until event.pointerCount) {
                    val pointerProperty = MotionEvent.PointerProperties()
                    event.getPointerCoords(i, oldCoords)
                    event.getPointerProperties(i, pointerProperty)
                    pointerCoords[i] = MotionEvent.PointerCoords()
                    pointerCoords[i]!!.apply {
                        x = oldCoords.x / mDisplayRatio
                        y = oldCoords.y / mDisplayRatio
                    }
                    pointerProperties[i] = pointerProperty
                }
                val newEvent = MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount, pointerProperties, pointerCoords, event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
                displayAdapter.onTouch(newEvent)
                newEvent.recycle()
                true
            }
            svMirror.holder.addCallback(object: SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    displayAdapter.addMirror(svMirror.surfaceControl)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    displayAdapter.removeMirror(svMirror.surfaceControl)
                }
            })
        }
        showController()
    }

    private fun initLayoutParams() {
        mControllerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        mMirrorLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            ,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 小窗：尺寸跟着内容走，位置由 x/y 决定（gravity 必须是 START|TOP，
            // 否则 x/y 的含义会变成相对其它边的偏移，拖动逻辑就对不上了）
            gravity = Gravity.START or Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun updateDipslaySize(){
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        val bounds = mWindowManager.currentWindowMetrics.bounds
        // “恰好铺满手机可用区”的基准比例：宽度扣掉阴影 padding，高度还要再扣掉
        // 顶栏和底部导航条，否则 100% 时小窗会比屏幕高
        val availableWidth = (bounds.width() - mMirrorShadowPadPx * 2).coerceAtLeast(1)
        val availableHeight =
            (bounds.height() - mMirrorShadowPadPx * 2 - mMirrorChromeHeightPx).coerceAtLeast(1)
        mMirrorFitRatio = (availableWidth.toFloat() / mDisplayWidth)
            .coerceAtMost(availableHeight.toFloat() / mDisplayHeight)
        mMirrorScale = mMirrorScale.coerceIn(minMirrorScale(), 1f)
        mDisplayRatio = mMirrorFitRatio * mMirrorScale
        mMirrorBinding?.apply {
            // buffer 保持车机原生分辨率，view 尺寸只做缩放，改大小是纯 GPU 拉伸
            svMirror.holder.setFixedSize(mDisplayWidth, mDisplayHeight)
            svMirror.updateLayoutParams{
                width = (mDisplayWidth * mDisplayRatio).toInt()
                height = (mDisplayHeight * mDisplayRatio).toInt()
            }
            updateRecentTaskPanelSize()
        }
        if (mMirrorStatus) {
            mMirrorBinding?.root?.post { clampMirrorPosition() }
        }
    }

    /**
     * 多任务面板跟着 sv_mirror 的实际尺寸走。
     *
     * 面板不能用 match_parent：小窗是 wrap_content，FrameLayout 在测量阶段会先按
     * AT_MOST 量一次面板，图标网格的高度就会把卡片撑得比车机画面还高，面板溢出
     * 卡片边界（看起来就是“错位”）。这里显式写死成和车机画面等大，并按可用宽度
     * 换算网格列数，缩放后依然贴合。
     */
    private fun updateRecentTaskPanelSize() {
        val binding = mMirrorBinding ?: return
        val width = (mDisplayWidth * mDisplayRatio).toInt().coerceAtLeast(1)
        val height = (mDisplayHeight * mDisplayRatio).toInt().coerceAtLeast(1)
        binding.llRecentTask.updateLayoutParams {
            this.width = width
            this.height = height
        }
        // 左右两栏各占一半，减去 padding 后按参考条目宽度算列数
        val columnWidth = (width / 2 - mMirrorRecentPaddingPx * 2).coerceAtLeast(1)
        val spanCount = (columnWidth / mMirrorRecentItemWidthPx).coerceIn(1, 4)
        arrayOf(binding.rvRecentTaskLeft, binding.rvRecentTaskRight).forEach { rv ->
            (rv.layoutManager as? GridLayoutManager)?.takeIf { it.spanCount != spanCount }
                ?.spanCount = spanCount
        }
    }

    /** 缩到 @dimen/mirror_min_width 就不再往下走，免得小窗小到点不着 */
    private fun minMirrorScale(): Float {
        val fullWidth = mDisplayWidth * mMirrorFitRatio
        if (fullWidth <= 0f) return 1f
        return (mMirrorMinWidthPx / fullWidth).coerceIn(0.1f, 1f)
    }

    /**
     * 顶栏 / 底部导航条上的拖动：整窗平移。
     *
     * 这里直接吃掉事件（返回 true），因为拖动区本身没有点击行为；按钮各自有
     * OnClickListener，不会走到这里。
     */
    private fun onMirrorDragTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mMirrorDragLastX = event.rawX
                mMirrorDragLastY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val binding = mMirrorBinding ?: return true
                mMirrorLayoutParams.x += (event.rawX - mMirrorDragLastX).toInt()
                mMirrorLayoutParams.y += (event.rawY - mMirrorDragLastY).toInt()
                mMirrorDragLastX = event.rawX
                mMirrorDragLastY = event.rawY
                tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clampMirrorPosition()
        }
        return true
    }

    /**
     * 右下角把手拖动：等比缩放。
     *
     * 以按下点为基准，用对角位移量换算成新的 mMirrorScale，再回到
     * updateDipslaySize() 统一改 mDisplayRatio —— 触摸转发也读这个字段，
     * 所以画面和点击位置始终一致。
     *
     * 没进入拖动前把事件交回 View（返回 false），长按恢复默认大小才有机会触发；
     * 一旦开始拖动就补一个 ACTION_CANCEL 掐掉 View 排队中的长按，避免拖着拖着
     * 突然弹出“已恢复默认大小”。
     */
    private fun onMirrorResizeTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mResizeStartRawX = event.rawX
                mResizeStartRawY = event.rawY
                mResizeStartScale = mMirrorScale
                mResizing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val fullWidth = mDisplayWidth * mMirrorFitRatio
                if (fullWidth <= 0f) return mResizing
                // 右下方向为放大：把 dx、dy 投影到对角线上，避免斜拖时手感突变
                val dx = event.rawX - mResizeStartRawX
                val dy = event.rawY - mResizeStartRawY
                if (!mResizing) {
                    if (sqrt(dx.pow(2) + dy.pow(2)) <= 5f) return false
                    mResizing = true
                    v.onTouchEvent(MotionEvent.obtain(event).apply {
                        action = MotionEvent.ACTION_CANCEL
                    })
                }
                val aspect = (mDisplayHeight.toFloat() / mDisplayWidth).coerceAtLeast(0.01f)
                val delta = (dx + dy / aspect) / 2f
                val target = mResizeStartScale + delta / fullWidth
                val clamped = target.coerceIn(minMirrorScale(), 1f)
                if (abs(clamped - mMirrorScale) >= 0.001f) {
                    mMirrorScale = clamped
                    updateDipslaySize()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mMirrorBinding?.root?.post { clampMirrorPosition() }
                if (mResizing) {
                    mResizing = false
                    return true
                }
            }
        }
        return if (mResizing) true else v.onTouchEvent(event)
    }

    /** 把小窗收回屏幕内；比屏幕大时允许负偏移，这样超出的部分还能拖着看 */
    private fun clampMirrorPosition() {
        val binding = mMirrorBinding ?: return
        if (!mMirrorStatus) return
        val bounds = mWindowManager.currentWindowMetrics.bounds
        val width = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val height = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight
        if (width <= 0 || height <= 0) return
        val slackX = bounds.width() - width
        val slackY = bounds.height() - height
        mMirrorLayoutParams.x = mMirrorLayoutParams.x.coerceIn(minOf(0, slackX), maxOf(0, slackX))
        mMirrorLayoutParams.y = mMirrorLayoutParams.y.coerceIn(minOf(0, slackY), maxOf(0, slackY))
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
    }

    /** 长按缩放把手：回到 100% 并重新居中 */
    private fun resetMirrorSizeAndPosition() {
        mMirrorScale = 1f
        updateDipslaySize()
        mMirrorBinding?.root?.post { centerMirror() }
    }

    /** 首次显示（以及恢复默认）时把小窗放到屏幕中间 */
    private fun centerMirror() {
        val binding = mMirrorBinding ?: return
        val bounds = mWindowManager.currentWindowMetrics.bounds
        val width = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val height = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight
        if (width <= 0 || height <= 0) return
        mMirrorLayoutParams.x = ((bounds.width() - width) / 2).coerceAtLeast(0)
        mMirrorLayoutParams.y = ((bounds.height() - height) / 2).coerceAtLeast(0)
        mMirrorPositioned = true
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
    }

    suspend fun onResume(width: Int, height: Int){
        mDisplayWidth = width
        mDisplayHeight = height
        mMirrorBinding?.tvVirtualDisplayInfo?.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
        interactiveMonitor.init()
        mDestroyJob?.cancelAndJoin()
        updateDipslaySize()
        mControllerBinding?.apply {
            tvDestroyTime.visibility = View.GONE
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        showController()
    }

    suspend fun onDestroyPromptly() {
        interactiveMonitor.release()
        toggleDisplayPower(true)
        mDestroyJob?.cancelAndJoin()
        close()
    }
    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        interactiveMonitor.release()
        toggleDisplayPower(true)
        mDestroyJob?.cancelAndJoin()

        if(mDelayDestroyTime == 0){
            close()
            onDestroySucceed()
            return
        }
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.GONE
            tvDestroyTime.setOnClickListener {
                mDestroyJob?.cancel()
                close()
                onDestroySucceed()
            }
            mDestroyJob = flow {
                for (i in mDelayDestroyTime downTo 0) {
                    emit(i)
                    delay(1000)
                }
            }.onStart {
                tvDestroyTime.visibility = View.VISIBLE
            }.onEach {
                if(mControllerStatus){
                    tvDestroyTime.text = "${it}S"
                }
            }.onCompletion {
                if(it == null){
                    close()
                    onDestroySucceed()
                }
            }.launchIn(CoroutineScope(Dispatchers.Main))
        }
    }

    fun toggleDisplayPower(displayPower: Boolean = !mDisplayPower){
        try {
            if(mScreenOffReplaceLockScreen){
                mDisplayPower = displayPower
                if(mDisplayPower){
                    SurfaceControlHidden.setDisplayPowerMode(SurfaceControlHidden.getInternalDisplayToken(), SurfaceControlHidden.POWER_MODE_NORMAL)
                } else {
                    SurfaceControlHidden.setDisplayPowerMode(SurfaceControlHidden.getInternalDisplayToken(), SurfaceControlHidden.POWER_MODE_OFF)
                }
            } else {
                Instances.powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "${BuildConfig.APPLICATION_ID}:wakeup").apply {
                    acquire()
                    release()
                }
            }
        } catch (e : Throwable){
            log(TAG, "", e)
        }
    }

    private fun showController(){
        if(mMirrorStatus){
            hideMirror()
        }
        if(mControllerStatus) return
        mControllerBinding?.apply {
            tryOrNull { mWindowManager.addView(root, mControllerLayoutParams) }
            applyControllerCollapsedState()
            root.post {
                applyControllerDockPosition()
            }
            mChangeAlphaCountDownTimer.start()
            mControllerStatus = true
        }
    }
    private fun hideController(){
        if(!mControllerStatus) return
        mControllerBinding?.apply {
            tryOrNull { mWindowManager.removeView(root) }
            mControllerStatus = false
        }
    }

    private fun showMirror(){
        if(mMirrorStatus) return
        hideRecentTask()
        mMirrorBinding?.apply {
            tryOrNull { mWindowManager.addView(root, mMirrorLayoutParams) }
            mMirrorStatus = true
            // 第一次弹出时居中；之后保留用户上次拖到的位置，只做越界回收
            root.post {
                if (mMirrorPositioned) clampMirrorPosition() else centerMirror()
            }
        }
    }
    private fun hideMirror(){
        if(!mMirrorStatus) return
        mMirrorBinding?.apply {
            tryOrNull { mWindowManager.removeView(root) }
            mMirrorStatus = false
        }
    }

    private fun toggleRecentTask(){
        mMirrorBinding?.apply {
            if(llRecentTask.visibility == View.VISIBLE){
                hideRecentTask()
                return
            }
            llRecentTask.visibility = View.VISIBLE
            updateRecentTaskPanelSize()
            runIO {
                // 小窗只显示图标，不取快照
                displayAdapter.getRecentTask(withSnapshot = false).also {recentTask ->
                    runMain {
                        (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.virtualDisplay)
                        (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.mainDisplay)
                    }
                }
            }
        }
    }
    private fun hideRecentTask(){
        mMirrorBinding?.apply {
            llRecentTask.visibility = View.GONE
            (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.clearItem()
            (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.clearItem()
        }
    }

    private fun expandController(){
        setControllerCollapsed(false)
    }
    private fun collapseController(){
        setControllerCollapsed(true)
    }

    private fun setControllerCollapsed(collapsed: Boolean) {
        mControllerCollapsed = collapsed
        applyControllerCollapsedState()
        mControllerBinding?.root?.post {
            applyControllerDockPosition()
        }
    }

    private fun applyControllerCollapsedState() {
        mControllerBinding?.apply {
            cvPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            cvHandle.visibility = if (mControllerCollapsed) View.VISIBLE else View.GONE
            llPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            ibExpand.visibility = View.GONE
        }
    }

    private fun applyControllerDockPosition() {
        val binding = mControllerBinding ?: return
        val displayMetrics = mContext.resources.displayMetrics
        val visibleWidth = getControllerVisibleWidth(binding)
        val visibleHeight = getControllerVisibleHeight(binding)
        if (visibleWidth <= 0 || visibleHeight <= 0) return

        mControllerLayoutParams.x = if (mControllerCollapsed) {
            if (mControllerDockRight) {
                displayMetrics.widthPixels - mControllerPeekPx
            } else {
                -(visibleWidth - mControllerPeekPx)
            }
        } else {
            if (mControllerDockRight) {
                displayMetrics.widthPixels - visibleWidth
            } else {
                0
            }
        }
        mControllerLayoutParams.y = mControllerLayoutParams.y.coerceIn(
            0,
            (displayMetrics.heightPixels - visibleHeight).coerceAtLeast(0)
        )
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mControllerLayoutParams) }
    }

    private fun getControllerVisibleWidth(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.width.takeIf { it > 0 } ?: binding.cvHandle.measuredWidth
        } else {
            binding.cvPanel.width.takeIf { it > 0 } ?: binding.cvPanel.measuredWidth
        }
    }

    private fun getControllerVisibleHeight(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.height.takeIf { it > 0 } ?: binding.cvHandle.measuredHeight
        } else {
            binding.cvPanel.height.takeIf { it > 0 } ?: binding.cvPanel.measuredHeight
        }
    }

    private fun close() {
        mChangeAlphaCountDownTimer.cancel()
        hideMirror()
        hideController()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return mControllerBinding?.run {
            var isDrag = false
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.apply {
                        setTag(R.id.is_drag, false)
                        setTag(R.id.drag_last_x, event.rawX)
                        setTag(R.id.drag_last_y, event.rawY)
                        setTag(R.id.drag_distance, 0f)
                    }
                    mChangeAlphaCountDownTimer.cancel()
                    ViewCompat.animate(root).setDuration(200).alpha(0.9f).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val dragMoveX = event.rawX
                    val dragMoveY = event.rawY
                    val dragLastX = (v.getTag(R.id.drag_last_x) as? Float ?: 0f)
                    val dragLastY = (v.getTag(R.id.drag_last_y) as? Float ?: 0f)
                    if(!isDrag){
                        val dragSumDistance = abs(sqrt((dragMoveX - dragLastX).pow(2) + (dragMoveY - dragLastY).pow(2))) + (v.getTag(R.id.drag_distance) as? Float ?: 0f)
                        if(dragSumDistance > 5f){
                            isDrag = true
                            v.apply {
                                setTag(R.id.is_drag, true)
                                onTouchEvent(MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                })
                            }
                        } else {
                            v.setTag(R.id.drag_distance, dragSumDistance)
                        }
                    }
                    mWindowManager.updateViewLayout(root, mControllerLayoutParams.apply {
                        x = (dragMoveX - dragLastX + x).toInt()
                        y = (dragMoveY - dragLastY + y).toInt()
                    })
                    v.apply {
                        setTag(R.id.drag_last_x, dragMoveX)
                        setTag(R.id.drag_last_y, dragMoveY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val displayMetrics = mContext.resources.displayMetrics
                    mControllerDockRight = event.rawX > (displayMetrics.widthPixels / 2f)
                    applyControllerDockPosition()
                    mChangeAlphaCountDownTimer.start()
                }
            }
            return if(isDrag) true else v.onTouchEvent(event)
        } ?: false
    }



}
