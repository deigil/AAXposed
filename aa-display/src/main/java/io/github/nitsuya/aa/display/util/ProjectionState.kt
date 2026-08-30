package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * “当前是否正在投屏” 的跨进程广播位。
 *
 * system_server（[io.github.nitsuya.aa.display.xposed.CoreManagerService]）是唯一写入方，
 * 普通应用进程（如 Gboard，见
 * [io.github.nitsuya.aa.display.xposed.hook.GboardImeHook]）只读。
 * 用 Settings.Global 而不是 Binder，是因为被 hook 的第三方进程不该被迫 bind 我们的服务，
 * 而 Settings.Global 的读取不需要任何权限。
 *
 * 与 [CoolwalkRailStore] / [EyeCareSuspender] 同一套跨开机判据：Settings.Global 跨开机存活，
 * 而 [SystemClock.uptimeMillis] 每次开机归零，存的 uptime 比现在大就一定是上一次开机的残留。
 */
object ProjectionState {
    /** 1 = 投屏中。system_server 被杀时可能残留，用 [SETTINGS_ACTIVE_UPTIME_MS] 识别。 */
    const val SETTINGS_ACTIVE = "aadisplay_projection_active"

    /** 置位时刻的 uptime，用于跨开机残留识别。 */
    const val SETTINGS_ACTIVE_UPTIME_MS = "aadisplay_projection_active_uptime_ms"

    /** system_server 侧：投屏开始。软重连会重复调用，保持幂等。 */
    fun markActive(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return runCatching {
            Settings.Global.putLong(cr, SETTINGS_ACTIVE_UPTIME_MS, SystemClock.uptimeMillis())
            Settings.Global.putInt(cr, SETTINGS_ACTIVE, 1)
            true
        }.getOrDefault(false)
    }

    /** system_server 侧：投屏结束。 */
    fun markInactive(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return runCatching {
            Settings.Global.putInt(cr, SETTINGS_ACTIVE, 0)
            Settings.Global.putLong(cr, SETTINGS_ACTIVE_UPTIME_MS, 0L)
            true
        }.getOrDefault(false)
    }

    /**
     * 任意进程：是否投屏中。带跨开机残留判定 —— 上一次开机置位后没能清掉，
     * 这里直接视为未投屏，避免第三方进程被永久改变行为。
     */
    fun isActive(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return runCatching {
            if (Settings.Global.getInt(cr, SETTINGS_ACTIVE, 0) != 1) return false
            val since = Settings.Global.getLong(cr, SETTINGS_ACTIVE_UPTIME_MS, 0L)
            since <= SystemClock.uptimeMillis()
        }.getOrDefault(false)
    }

    fun isActive(context: Context?): Boolean = isActive(context?.contentResolver)

    /**
     * 客户端进程用：注册变化回调，把状态缓存在本地 volatile 里，
     * 避免在热路径（如每次 SharedPreferences 读取）上走 ContentProvider。
     * 返回注册成功与否；[onChanged] 会在注册时先回调一次当前值。
     */
    fun observe(context: Context, onChanged: (Boolean) -> Unit): Boolean {
        val cr = context.contentResolver ?: return false
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChanged(isActive(cr))
            }
        }
        return runCatching {
            cr.registerContentObserver(
                Settings.Global.getUriFor(SETTINGS_ACTIVE),
                false,
                observer,
            )
            onChanged(isActive(cr))
            true
        }.getOrDefault(false)
    }
}
