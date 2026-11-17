package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.ProjectionState
import io.github.nitsuya.aa.display.xposed.HookPreferences
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug

/**
 * Fix for Gboard occasionally opening as a **floating keyboard** during projection.
 *
 * ## Why this happens
 *
 * Gboard's keyboard mode (`KeyboardModeManager` / `KeyboardModeSwitcher`, enum
 * `1=NORMAL 2=ONE_HANDED 3=FLOATING 4=SPLIT`) is determined by two paths, both triggered by projection:
 *
 * 1. **Auto-float** (`KeyboardModeManager.ap()`): when device form factor is not tablet, if
 *    Phenotype flags `enable_auto_float_keyboard_in_landscape` / `_in_freeform` / `_in_multi_window`
 *    match and the corresponding persisted boolean `float_keyboard_in_landscape` / `float_keyboard_in_freeform` /
 *    `float_keyboard_in_multi_window` is true, `KeyboardModeSwitcher`'s temporary mode is set to 3 (FLOATING).
 *    Landscape check is `getConfiguration().orientation == ORIENTATION_LANDSCAPE`, which our 1280x720
 *    projection VirtualDisplay always satisfies.
 *
 * 2. **Sticky float** (`KeyboardModeUtils.h(Context)`): what actually determines initial mode is the
 *    persisted int `keyboard_mode_landscape` (selected for phone + landscape). Once written as 3 —
 *    either manually by the user or persisted by auto-float via `setKeyboardMode(..., updatePreferences=true)` —
 *    **every** subsequent landscape session (including every projection) opens in floating mode until
 *    changed back.
 *
 * Key point: device form factor comes from the phone rather than the target display, so Gboard still
 * reports `DEVICE_PHONE` during projection and uses the same "phone + landscape" keys as phone landscape.
 *
 * ## How we fix it
 *
 * Only **while projection is active** ([ProjectionState]), intercept Gboard's `SharedPreferences` reads:
 * - When a keyboard mode int reads 3 (FLOATING), return 1 (NORMAL);
 * - When `float_keyboard_in_*` booleans read true, return false so `ap()` returns false directly.
 *
 * Hook framework's `android.app.SharedPreferencesImpl` rather than Gboard's obfuscated `Preferences.get(...)`:
 * key names are resource strings (stable across versions), whereas obfuscated class names change every
 * release; this also avoids running DexKit on an 89MB APK inside the IME process.
 *
 * **Never writes** any Gboard preferences: the user's floating keyboard choice on the phone stays intact
 * and resumes immediately when projection disconnects.
 *
 * Known trade-off: interception is at the preference layer and cannot distinguish whether a read is for
 * the head unit or phone screen, so **phone landscape also won't float during projection** (same global
 * trade-off as [io.github.nitsuya.aa.display.util.EyeCareSuspender]).
 *
 * Another known boundary: if Gboard already cached the mode in `KeyboardModeSwitcher` **before**
 * projection started and no config change / mode refresh occurs, that cached value won't be overridden.
 * In practice, moving the keyboard onto the head unit VD triggers a config change and re-reads.
 */
object GboardImeHook : BaseHook() {
    override val tagName: String = "AAD_GboardImeHook"

    const val PACKAGE_GBOARD = "com.google.android.inputmethod.latin"

    /** `KeyboardModeManager` enum: 1=NORMAL 2=ONE_HANDED 3=FLOATING 4=SPLIT. */
    private const val MODE_NORMAL = 1
    private const val MODE_FLOATING = 3

    /**
     * Persisted keys for current / previous keyboard mode (`KeyboardModePrefUtils.a()/c()` picks one
     * by form factor + orientation). Projection uses `keyboard_mode_landscape`; cover the rest too
     * so "restore previous mode" doesn't bring floating back.
     */
    private val KEYBOARD_MODE_INT_KEYS = hashSetOf(
        "keyboard_mode",
        "keyboard_mode_landscape",
        "desk_keyboard_mode",
        "keyboard_mode_foldable_keyboard_mode",
        "keyboard_mode_tablet_keyboard_mode",
        "previous_keyboard_mode",
        "previous_keyboard_mode_landscape",
        "desk_previous_keyboard_mode",
        "keyboard_mode_foldable_previous_keyboard_mode",
        "keyboard_mode_tablet_previous_keyboard_mode",
    )

    /** Auto-float + default-float toggles, treated as false during projection. */
    private val FLOAT_BOOL_KEYS = hashSetOf(
        "float_keyboard_in_landscape",
        "float_keyboard_in_freeform",
        "float_keyboard_in_multi_window",
        "float_keyboard_default",
        "desk_float_keyboard_default",
        "keyboard_mode_foldable_float_keyboard_default",
        "tablet_float_keyboard_default",
    )

    /** Cached config toggle to avoid hitting remote config on every preference read. */
    private var mEnabled: Boolean = true

    /** Local cache of [ProjectionState] maintained by ContentObserver; avoids ContentProvider on hot path. */
    @Volatile
    private var mProjecting: Boolean = false

    /** Fall back to throttled direct queries if observe registration fails, keeping the feature working. */
    @Volatile
    private var mObserved: Boolean = false

    private var mLastPollUptimeMs: Long = 0L

    override fun init(target: HookTarget) {
        mEnabled = AADisplayConfig.FixGboardFloatingIme.get(HookPreferences.get())
        if (!mEnabled) {
            log(tagName, "disabled by config")
            return
        }

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(android.app.Instrumentation::class.java) {
            name == "callApplicationOnCreate" &&
                parameterCount == 1 &&
                parameterTypes[0] == android.app.Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            startProjectionWatch(InitFields.appContext)
            hookPreferenceReads()
        }
    }

    private fun startProjectionWatch(context: Context) {
        mObserved = ProjectionState.observe(context) { active ->
            if (mProjecting != active) {
                log(tagName, "projection active=$active")
            }
            mProjecting = active
        }
        if (!mObserved) {
            log(tagName, "ProjectionState observe failed; falling back to throttled polling")
            mProjecting = ProjectionState.isActive(context)
        }
    }

    /**
     * Fallback when observe fails: query Settings.Global at most once every 2s
     * to avoid hitting ContentProvider on every preference read.
     */
    private fun isProjecting(): Boolean {
        if (mObserved) return mProjecting
        val now = android.os.SystemClock.uptimeMillis()
        if (now - mLastPollUptimeMs > 2_000L) {
            mLastPollUptimeMs = now
            mProjecting = runCatching {
                ProjectionState.isActive(InitFields.appContext)
            }.getOrDefault(false)
        }
        return mProjecting
    }

    private fun hookPreferenceReads() {
        val impl = runCatching { Class.forName("android.app.SharedPreferencesImpl") }
            .onFailure { e -> log(tagName, "SharedPreferencesImpl not found", e) }
            .getOrNull() ?: return

        runCatching {
            findMethod(impl) {
                name == "getInt" &&
                    parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookAfter { param ->
                if (param.result != MODE_FLOATING) return@hookAfter
                val key = param.args[0] as? String ?: return@hookAfter
                if (key !in KEYBOARD_MODE_INT_KEYS) return@hookAfter
                if (!isProjecting()) return@hookAfter
                param.result = MODE_NORMAL
                logDebug(tagName, "keyboard mode $key: FLOATING -> NORMAL")
            }
        }.onFailure { e -> log(tagName, "hook SharedPreferencesImpl.getInt failed", e) }

        runCatching {
            findMethod(impl) {
                name == "getBoolean" &&
                    parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.hookAfter { param ->
                if (param.result != true) return@hookAfter
                val key = param.args[0] as? String ?: return@hookAfter
                if (key !in FLOAT_BOOL_KEYS) return@hookAfter
                if (!isProjecting()) return@hookAfter
                param.result = false
                logDebug(tagName, "auto-float pref $key: true -> false")
            }
        }.onFailure { e -> log(tagName, "hook SharedPreferencesImpl.getBoolean failed", e) }

        log(tagName, "preference read hooks installed")
    }
}
