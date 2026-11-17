package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * MIUI / HyperOS eye-care (paper) mode is implemented by `DisplayFeatureManagerService` as a
 * SurfaceFlinger **per-display colorTransformMatrix**, where `getEffectedDisplayIndex()` on
 * non-foldables always returns `[0]`, and SF broadcasts that matrix to **all** displays — including
 * the AA projection VirtualDisplay, tinting the head unit yellow when eye-care is on.
 *
 * MIUI has no API to exclude a specific display:
 * - `projectionDisplayFlag` / `isCastMode` / `isScreenProjection` do not participate in color
 *   transforms (verified: projection VD already has `projectionDisplayFlag=1` yet still gets tinted);
 * - `read_apps_only` / `EffectScopeManager` only take effect on `IS_GREEN_MOUNTAIN_EYECARE_V3` devices;
 * - framework has no display-level color matrix API (only per-layer `SurfaceControl$Transaction.setColorTransform`).
 *
 * Nor can `screen_game_mode`'s `mForceDisableEyeCare` bit be used: it ramps `setScreenEffect mode=3`
 * to 0, but **the SF matrix stays unchanged**, leaving the screen yellow.
 *
 * Therefore the only effective toggle is `Settings.System.screen_paper_mode_enabled`: setting 0
 * resets the matrix to neutral, and setting it back to 1 restores immediately per `screen_paper_mode_level`
 * etc. (we never touch level keys).
 *
 * Trade-off: suppression is **global** — during projection the phone's own screen also loses eye-care
 * and Settings shows eye-care as off. This is dictated by MIUI's implementation.
 *
 * Lifecycle: [suspend] is called when VD is created, [restore] when VD is destroyed;
 * the original value is persisted in module-owned Settings.Global keys, and [recoverStaleSuspend]
 * rolls back any leftover state in `systemReady()` if system_server was killed or rebooted mid-projection.
 */
object EyeCareSuspender {
    /** MIUI eye-care master toggle, also the only key on this device that changes the SF colorTransformMatrix. */
    private const val KEY_PAPER_MODE_ENABLED = "screen_paper_mode_enabled"

    /** Module-owned key: original eye-care toggle value before projection (-1 = not suspended). */
    const val SETTINGS_SAVED_PAPER_MODE = "aadisplay_eyecare_saved_paper_mode"

    /** Module-owned key: uptime when suspended, used to detect stale values across reboots. */
    const val SETTINGS_SUSPEND_UPTIME_MS = "aadisplay_eyecare_suspend_uptime_ms"

    private const val NONE = -1

    private val mHandler = Handler(Looper.getMainLooper())

    /** Watch for eye-care being re-enabled during projection (schedule/user), suppress it and record intended value. */
    private var mObserver: ContentObserver? = null

    /** Result is only returned to the caller for logging, avoiding xposed log dependencies here. */
    sealed class Result {
        object Disabled : Result()
        object Unsupported : Result()
        object AlreadyActive : Result()
        object NotActive : Result()
        object NothingToDo : Result()
        data class Applied(val savedValue: Int) : Result()
        data class Restored(val toValue: Int) : Result()
        data class Failed(val error: Throwable) : Result()
    }

    /**
     * Whether this device has MIUI eye-care. If not, write no keys at all
     * to avoid leaving meaningless settings on AOSP / other OEMs.
     */
    private fun isSupported(cr: ContentResolver): Boolean =
        runCatching { Settings.System.getString(cr, KEY_PAPER_MODE_ENABLED) != null }
            .getOrDefault(false)

    private fun readPaperMode(cr: ContentResolver): Int =
        runCatching { Settings.System.getInt(cr, KEY_PAPER_MODE_ENABLED, 0) }.getOrDefault(0)

    private fun readSaved(cr: ContentResolver): Int =
        runCatching { Settings.Global.getInt(cr, SETTINGS_SAVED_PAPER_MODE, NONE) }.getOrDefault(NONE)

    /**
     * Settings.Global persists across reboots while [SystemClock.uptimeMillis] resets to 0 on boot:
     * a stored uptime greater than now is guaranteed to be from a previous boot
     * (same criterion as [CoolwalkRailStore.isCrossBootStale]).
     */
    private fun isCrossBootStale(cr: ContentResolver): Boolean {
        val saved = runCatching {
            Settings.Global.getLong(cr, SETTINGS_SUSPEND_UPTIME_MS, 0L)
        }.getOrDefault(0L)
        return saved > SystemClock.uptimeMillis()
    }

    private fun writeSaved(cr: ContentResolver, value: Int) {
        runCatching {
            Settings.Global.putInt(cr, SETTINGS_SAVED_PAPER_MODE, value)
            Settings.Global.putLong(cr, SETTINGS_SUSPEND_UPTIME_MS, SystemClock.uptimeMillis())
        }
    }

    private fun clearSaved(cr: ContentResolver) {
        runCatching {
            Settings.Global.putInt(cr, SETTINGS_SAVED_PAPER_MODE, NONE)
            Settings.Global.putLong(cr, SETTINGS_SUSPEND_UPTIME_MS, 0L)
        }
    }

    /** Projection started: turn off eye-care master toggle and save original value. Idempotent on soft reconnect. */
    fun suspend(context: Context, enabled: Boolean): Result {
        if (!enabled) return Result.Disabled
        return runCatching {
            val cr = context.contentResolver
            if (!isSupported(cr)) return Result.Unsupported
            val alreadySuspended = readSaved(cr) != NONE && !isCrossBootStale(cr)
            if (alreadySuspended) {
                if (readPaperMode(cr) != 0) Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
                startObserver(context)
                return Result.AlreadyActive
            }
            val original = readPaperMode(cr)
            if (original == 0) {
                // Eye-care is already off: write nothing, no restore needed on disconnect.
                return Result.NothingToDo
            }
            writeSaved(cr, original)
            Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
            startObserver(context)
            Result.Applied(original)
        }.getOrElse { Result.Failed(it) }
    }

    /** Projection ended: write back the recorded user-intended value. */
    fun restore(context: Context): Result {
        return runCatching {
            val cr = context.contentResolver
            stopObserver(cr)
            val saved = readSaved(cr)
            if (saved == NONE) return Result.NotActive
            if (readPaperMode(cr) != saved) {
                Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, saved)
            }
            clearSaved(cr)
            Result.Restored(saved)
        }.getOrElse { Result.Failed(it) }
    }

    /**
     * Boot fallback: if suspended in a previous boot without restoring (system_server killed / reboot),
     * eye-care would stay off permanently. Call once in `systemReady()` to restore it.
     */
    fun recoverStaleSuspend(context: Context): Result {
        return runCatching {
            val cr = context.contentResolver
            if (readSaved(cr) == NONE) return Result.NotActive
            if (!isCrossBootStale(cr)) return Result.NotActive
            restore(context)
        }.getOrElse { Result.Failed(it) }
    }

    /**
     * An eye-care schedule (`paper_mode_scheduler_type`) or manual toggle may re-enable eye-care
     * mid-projection, turning the head unit yellow immediately. Suppress it back to 0 and update
     * the user-intended value to 1 so it restores on disconnect without swallowing user intent.
     */
    private fun startObserver(context: Context) {
        if (mObserver != null) return
        val cr = context.contentResolver
        val observer = object : ContentObserver(mHandler) {
            override fun onChange(selfChange: Boolean) {
                runCatching {
                    if (readSaved(cr) == NONE) return
                    val current = readPaperMode(cr)
                    if (current == 0) return
                    writeSaved(cr, current)
                    Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
                }
            }
        }
        runCatching {
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_PAPER_MODE_ENABLED),
                false,
                observer,
            )
            mObserver = observer
        }
    }

    private fun stopObserver(cr: ContentResolver) {
        val observer = mObserver ?: return
        mObserver = null
        runCatching { cr.unregisterContentObserver(observer) }
    }
}
