package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.IBinder
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

    /** Transparent padding reserved on controller root for card shadow; dock coords subtract it (see applyControllerDockPosition) */
    private val mControllerShadowPadPx by lazy {
        mContext.resources.getDimensionPixelSize(R.dimen.window_shadow_pad)
    }

    /**
     * Geometry state of the mirror floating window (MIUI freeform: draggable and proportionally resizable).
     *
     * mMirrorFitRatio is the scale that just fits the usable phone area, while mMirrorScale is the
     * user's multiplier on top (adjusted via the bottom-right handle); their product is mDisplayRatio.
     * Touch forwarding divides by mDisplayRatio to map coordinates back to head unit resolution, so
     * resizing must only go through updateDipslaySize(), otherwise image and touch targets will desync.
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
    /** Recents panel: reference width of a single icon item / RecyclerView padding for grid column calculation */
    private val mMirrorRecentItemWidthPx by lazy {
        mContext.resources.getDimensionPixelSize(R.dimen.mirror_recent_item_width).coerceAtLeast(1)
    }
    private val mMirrorRecentPaddingPx by lazy {
        (mContext.resources.displayMetrics.density * 4f).toInt()
    }
    /** Height occupied by top bar + bottom nav bar, subtracted from screen height when computing usable space */
    private val mMirrorChromeHeightPx by lazy {
        mContext.resources.let {
            it.getDimensionPixelSize(R.dimen.mirror_top_bar_height) +
                it.getDimensionPixelSize(R.dimen.mirror_nav_btn_size) +
                (it.displayMetrics.density * 8f).toInt()
        }
    }

    /**
     * Floating window must attach to the phone main screen (display 0).
     *
     * mContext originates from AMS's mUiContext; when DisplayWindow is constructed right after
     * gearhead switches displays, it may still be bound to a just-removed virtual display, causing
     * addView to throw InvalidDisplayException ("Attempted to add window to a display that does
     * not exist") and be swallowed by tryOrNull on the first showController. Explicitly bind to
     * DEFAULT_DISPLAY here so currentWindowMetrics also reads the phone main screen's dimensions.
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

    /**
     * Android 16 removed SurfaceControl.getInternalDisplayToken().  Obtain the primary physical
     * display token from the Android 16 SurfaceControl API, then retain the old lookup for
     * releases which still expose it.
     */
    private val mInternalDisplayToken: IBinder by lazy {
        runCatching {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val displayIds = surfaceControl
                .getDeclaredMethod("getPhysicalDisplayIds")
                .apply { isAccessible = true }
                .invoke(null) as LongArray
            require(displayIds.isNotEmpty()) { "no physical displays" }
            surfaceControl
                .getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, displayIds.first()) as IBinder
        }.onSuccess {
            log(TAG, "display power: using SurfaceControl physical display token")
        }.getOrElse { physicalTokenError ->
            runCatching {
                SurfaceControlHidden.getInternalDisplayToken()
            }.onSuccess {
                log(TAG, "display power: using legacy SurfaceControl display token")
            }.getOrElse { legacyError ->
                throw IllegalStateException(
                    "cannot obtain internal display token; physicalDisplay=$physicalTokenError, " +
                        "SurfaceControl=$legacyError"
                )
            }
        }
    }

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
            if (AADisplayConfig.ForceAppFullscreenOnProjection.get(CoreManagerService.config)) {
                AndroidHook.AppFullscreenOnProjection.hook()
            }
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
            AndroidHook.AppFullscreenOnProjection.unHook()
        }
    }



    init {
        runCatching {
            with(ContextThemeWrapper(mContext, R.style.Theme_AADisplay_Window)){
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
                // Freeform mirror window is no longer full-screen and overlays recents on top of the
                // head unit image, so no status bar padding is needed.
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
            // Dragging top bar / empty space on bottom nav bar moves the entire floating window
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
            // Freeform window: size follows content, position is driven by x/y (gravity must be START|TOP,
            // otherwise x/y become offsets from other edges and drag logic breaks)
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
        // Base ratio that fits the usable phone area: width subtracts shadow padding, height also
        // subtracts top bar and bottom nav bar so 100% scale doesn't exceed the screen height
        val availableWidth = (bounds.width() - mMirrorShadowPadPx * 2).coerceAtLeast(1)
        val availableHeight =
            (bounds.height() - mMirrorShadowPadPx * 2 - mMirrorChromeHeightPx).coerceAtLeast(1)
        mMirrorFitRatio = (availableWidth.toFloat() / mDisplayWidth)
            .coerceAtMost(availableHeight.toFloat() / mDisplayHeight)
        mMirrorScale = mMirrorScale.coerceIn(minMirrorScale(), 1f)
        mDisplayRatio = mMirrorFitRatio * mMirrorScale
        mMirrorBinding?.apply {
            // Keep buffer at native head unit resolution; only scale view bounds (pure GPU stretch)
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
     * Recents panel follows sv_mirror's actual dimensions.
     *
     * Cannot use match_parent: the floating window is wrap_content, so FrameLayout measures
     * the panel with AT_MOST first, stretching the card taller than the head unit screen and
     * overflowing card bounds. Explicitly match the head unit image size and compute grid columns
     * from available width so it stays aligned after scaling.
     */
    private fun updateRecentTaskPanelSize() {
        val binding = mMirrorBinding ?: return
        val width = (mDisplayWidth * mDisplayRatio).toInt().coerceAtLeast(1)
        val height = (mDisplayHeight * mDisplayRatio).toInt().coerceAtLeast(1)
        binding.llRecentTask.updateLayoutParams {
            this.width = width
            this.height = height
        }
        // Left and right columns each take half; subtract padding and divide by reference item width for column count
        val columnWidth = (width / 2 - mMirrorRecentPaddingPx * 2).coerceAtLeast(1)
        val spanCount = (columnWidth / mMirrorRecentItemWidthPx).coerceIn(1, 4)
        arrayOf(binding.rvRecentTaskLeft, binding.rvRecentTaskRight).forEach { rv ->
            (rv.layoutManager as? GridLayoutManager)?.takeIf { it.spanCount != spanCount }
                ?.spanCount = spanCount
        }
    }

    /** Stop shrinking below @dimen/mirror_min_width so the window stays tappable */
    private fun minMirrorScale(): Float {
        val fullWidth = mDisplayWidth * mMirrorFitRatio
        if (fullWidth <= 0f) return 1f
        return (mMirrorMinWidthPx / fullWidth).coerceIn(0.1f, 1f)
    }

    /**
     * Dragging on top bar / bottom nav bar: translates the entire window.
     *
     * Consumes the event (returns true) because the drag area has no click action;
     * buttons have their own OnClickListeners and won't hit this path.
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
     * Bottom-right handle drag: proportional resize.
     *
     * Uses diagonal displacement from touch-down to compute new mMirrorScale and calls
     * updateDipslaySize() to update mDisplayRatio — touch forwarding reads the same field,
     * keeping image and tap coordinates in sync.
     *
     * Passes events back to View (returns false) before drag starts so long-press reset can trigger;
     * once dragging begins, injects ACTION_CANCEL to cancel queued long-press detection.
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
                // Bottom-right enlarges: project dx, dy onto diagonal to avoid feel jumps on angled drags
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

    /** Clamp floating window within screen bounds; allow negative offset if larger than screen so overflow can be panned */
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

    /** Long-press resize handle: reset to 100% and re-center */
    private fun resetMirrorSizeAndPosition() {
        mMirrorScale = 1f
        updateDipslaySize()
        mMirrorBinding?.root?.post { centerMirror() }
    }

    /** Center floating window on screen on first show (and on reset) */
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
                if (mDisplayPower == displayPower) return
                val mode = if (displayPower) {
                    SurfaceControlHidden.POWER_MODE_NORMAL
                } else {
                    SurfaceControlHidden.POWER_MODE_OFF
                }
                SurfaceControlHidden.setDisplayPowerMode(mInternalDisplayToken, mode)
                mDisplayPower = displayPower
                log(TAG, "display power: ${if (displayPower) "on" else "off"}")
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
            // Center on first show; afterwards keep user's last dragged position and only clamp out-of-bounds
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
                // Mirror window shows icons only, skip snapshots
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

        // Root layout reserves transparent padding for card shadow, offsetting the card top-left from
        // the window origin by one pad while x/y position the window itself. All four dock cases compute
        // where the card belongs, so subtract one pad uniformly; otherwise the card sits one pad off the edge.
        val pad = mControllerShadowPadPx
        val dockX = if (mControllerCollapsed) {
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
        mControllerLayoutParams.x = dockX - pad
        mControllerLayoutParams.y = mControllerLayoutParams.y.coerceIn(
            -pad,
            (displayMetrics.heightPixels - visibleHeight - pad).coerceAtLeast(-pad)
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
