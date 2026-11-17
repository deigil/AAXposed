package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Config write mirror on the module app side.
 *
 * Under the modern Xposed API, hooked processes read **remote config in the framework daemon**
 * (SQLite, see [io.github.libxposed.service.RemotePreferences]), rather than the xml in the module's
 * own `shared_prefs` directory. UI ([io.github.nitsuya.aa.display.ui.setting.SettingsActivity]'s
 * `PreferenceFragmentCompat`, MainActivity toggles, etc.) still writes to local SharedPreferences —
 * the only storage the preference library supports — so we attach a listener here to sync every
 * local change to remote.
 *
 * Legacy frameworks (LSPosed without api 101 support) use [XposedInit][io.github.nitsuya.aa.display.xposed.XposedInit]
 * + `XSharedPreferences`, which reads that local xml directly; therefore local storage cannot be
 * removed and [SharedPreferencesAccess]'s permission-relaxing logic must be kept. Both paths share the same local data.
 */
object RemoteConfigMirror {

    private const val TAG = "AAD_RemoteConfigMirror"

    @Volatile
    private var installed = false

    /** Local storage. Strong reference: [SharedPreferences] listener registry is weak. */
    private var local: SharedPreferences? = null

    /** Remote config, populated once bound to the framework service. */
    @Volatile
    private var remote: SharedPreferences? = null

    /**
     * [XposedServiceHelper.registerListener] keeps only one listener and holds the callback chain
     * weakly, so this must be a long-lived object field.
     */
    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            val prefs = runCatching { service.getRemotePreferences(AADisplayConfig.ConfigName) }
                .onFailure { Log.w(TAG, "getRemotePreferences failed", it) }
                .getOrNull() ?: return
            remote = prefs
            Log.i(TAG, "xposed service bound: ${service.frameworkName} ${service.frameworkVersion}")
            syncAll()
        }

        override fun onServiceDied(service: XposedService) {
            Log.i(TAG, "xposed service died")
            remote = null
        }
    }

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null) pushKey(prefs, key)
    }

    /**
     * Called in [io.github.nitsuya.aa.display.Application.onCreate].
     * Installed once per app process; no-op when the framework is inactive.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val prefs = runCatching {
            SharedPreferencesAccess.openForHooks(context, AADisplayConfig.ConfigName)
        }.onFailure { Log.w(TAG, "open local config failed", it) }.getOrNull() ?: return
        local = prefs
        prefs.registerOnSharedPreferenceChangeListener(localListener)
        runCatching { XposedServiceHelper.registerListener(serviceListener) }
            .onFailure { Log.w(TAG, "registerListener failed", it) }
    }

    /** Full local -> remote sync. Runs once on bind to backfill existing config. */
    private fun syncAll() {
        val source = local ?: return
        val target = remote ?: return
        runCatching {
            val editor = target.edit()
            var count = 0
            for ((key, value) in source.all) {
                putValue(editor, key, value)
                count++
            }
            editor.apply()
            Log.i(TAG, "synced $count keys to remote preferences")
        }.onFailure { Log.w(TAG, "full sync failed", it) }
    }

    private fun pushKey(source: SharedPreferences, key: String) {
        val target = remote ?: return
        runCatching {
            val editor = target.edit()
            val value = source.all[key]
            if (value == null) editor.remove(key) else putValue(editor, key, value)
            editor.apply()
            Log.i(TAG, "pushed $key=$value to remote preferences")
        }.onFailure { Log.w(TAG, "push $key failed", it) }
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Log.w(TAG, "unsupported value type for $key: ${value.javaClass.name}")
        }
    }
}
