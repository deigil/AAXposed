package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Cross-process broadcast flag for whether projection is currently active.
 *
 * system_server ([io.github.nitsuya.aa.display.xposed.CoreManagerService]) is the sole writer;
 * regular app processes (such as Gboard, see [io.github.nitsuya.aa.display.xposed.hook.GboardImeHook])
 * are read-only. Uses Settings.Global instead of Binder so hooked third-party processes are not
 * forced to bind our service, and reading Settings.Global requires no permissions.
 *
 * Shares the same cross-boot staleness check as [CoolwalkRailStore] / [EyeCareSuspender]:
 * Settings.Global persists across reboots, whereas [SystemClock.uptimeMillis] resets to 0 on boot,
 * so a stored uptime greater than the current uptime is guaranteed to be a leftover from a previous boot.
 */
object ProjectionState {
    /** 1 = projecting. May remain if system_server is killed; validated with [SETTINGS_ACTIVE_UPTIME_MS]. */
    const val SETTINGS_ACTIVE = "aadisplay_projection_active"

    /** Uptime when the flag was set, used to detect stale values across reboots. */
    const val SETTINGS_ACTIVE_UPTIME_MS = "aadisplay_projection_active_uptime_ms"

    /** system_server side: projection started. Idempotent across soft reconnects. */
    fun markActive(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return runCatching {
            Settings.Global.putLong(cr, SETTINGS_ACTIVE_UPTIME_MS, SystemClock.uptimeMillis())
            Settings.Global.putInt(cr, SETTINGS_ACTIVE, 1)
            true
        }.getOrDefault(false)
    }

    /** system_server side: projection ended. */
    fun markInactive(cr: ContentResolver?): Boolean {
        if (cr == null) return false
        return runCatching {
            Settings.Global.putInt(cr, SETTINGS_ACTIVE, 0)
            Settings.Global.putLong(cr, SETTINGS_ACTIVE_UPTIME_MS, 0L)
            true
        }.getOrDefault(false)
    }

    /**
     * Any process: whether projection is active, with cross-boot staleness check — if set during a
     * previous boot and never cleared, treat as inactive so third-party processes are not permanently affected.
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
     * For client processes: register a change callback and cache state in a local volatile
     * to avoid hitting ContentProvider on hot paths (e.g. every SharedPreferences read).
     * Returns whether registration succeeded; [onChanged] is invoked once with the current value on registration.
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
