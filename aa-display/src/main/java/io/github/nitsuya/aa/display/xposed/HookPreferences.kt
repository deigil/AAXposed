package io.github.nitsuya.aa.display.xposed

import android.content.SharedPreferences

/**
 * Module config entry point on the hooked process side.
 *
 * Before migrating to libxposed, each hook created `XSharedPreferences(APPLICATION_ID, ConfigName)`
 * and called `canRead()` / `reload()`. That required `aadisplay_config.xml` to be world-readable,
 * which LSPosed 2.2.0 began warning on and 2.3.0 plans to remove.
 *
 * The modern API uses `XposedInterface.getRemotePreferences(group)`: config lives in the framework
 * daemon's SQLite and is read over Binder; the in-process implementation is **real-time**
 * (the framework pushes changes), so there is no `reload()` equivalent.
 *
 * Both sources are injected by the entry class, and hooks only use [get] / [latest]:
 * - [ModernInit] injects `getRemotePreferences`;
 * - [XposedInit] on legacy LSPosed injects `XSharedPreferences` + a `reload()` refresher.
 *
 * The resolved result (including null on failure) is cached to avoid an IPC on every hook trigger.
 */
object HookPreferences {
    private const val TAG = "AAD_HookPreferences"

    private var supplier: (() -> SharedPreferences?)? = null
    private var refresher: ((SharedPreferences) -> Unit)? = null

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var resolved: Boolean = false

    /**
     * Inject config source. Called by the entry class before dispatching any hooks.
     *
     * @param refresher Refresh action before reading. `XSharedPreferences` needs `reload()`;
     *   remote config is real-time, so pass null.
     */
    @Synchronized
    fun install(
        supplier: () -> SharedPreferences?,
        refresher: ((SharedPreferences) -> Unit)? = null,
    ) {
        this.supplier = supplier
        this.refresher = refresher
        cached = null
        resolved = false
    }

    /** Get config; resolved once, returns null on failure (each config item has a default so features keep working). */
    fun get(): SharedPreferences? {
        if (resolved) return cached
        return resolve()
    }

    /**
     * Get config and refresh once first. Under legacy this equals `reload()`; under modern API it equals [get].
     * Used at sites that previously called `reload()` explicitly.
     */
    fun latest(): SharedPreferences? {
        val config = get() ?: return null
        refresher?.let { refresh ->
            runCatching { refresh(config) }.onFailure { e ->
                log(TAG, "refresh failed; using cached values", e)
            }
        }
        return config
    }

    @Synchronized
    private fun resolve(): SharedPreferences? {
        if (resolved) return cached
        val provider = supplier
        if (provider == null) {
            log(TAG, "no preferences source installed; using defaults")
            resolved = true
            return null
        }
        cached = runCatching { provider() }.onFailure { e ->
            log(TAG, "failed to obtain module preferences; using defaults", e)
        }.getOrNull()
        resolved = true
        if (cached == null) {
            log(TAG, "module preferences unavailable; using defaults")
        }
        return cached
    }
}
