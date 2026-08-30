package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object SharedPreferencesAccess {
    private const val HookPreferencesReadyVersionKey = "__AADisplayHookPreferencesReadyVersion"

    fun openForHooks(context: Context, name: String): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /**
     * PreferenceManager 只接受一个 mode 常量，且在内部 getSharedPreferences 时才抛
     * SecurityException（LSPosed 未激活时 MODE_WORLD_READABLE 在 N+ 上不被允许），
     * 无法就地 catch。这里先探测一次，把可用的 mode 交给 PreferenceManager。
     */
    fun resolveModeForHooks(context: Context, name: String): Int {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
            @Suppress("DEPRECATION")
            Context.MODE_WORLD_READABLE
        } catch (_: SecurityException) {
            Context.MODE_PRIVATE
        }
    }

    fun makeReadableForHooks(context: Context, name: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
                .edit()
                .putInt(HookPreferencesReadyVersionKey, 1)
                .commit()
        } catch (_: SecurityException) {
            makePrivatePreferencesReadable(context, name)
        }
    }

    private fun makePrivatePreferencesReadable(context: Context, name: String): Boolean {
        val dataDir = File(context.applicationInfo.dataDir)
        val prefsDir = File(dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "$name.xml")

        var ok = ensureExecutable(dataDir)
        if (prefsDir.exists()) {
            ok = ensureExecutable(prefsDir) && ok
        }
        if (prefsFile.exists()) {
            ok = ensureReadable(prefsFile) && ok
        }
        return ok
    }

    private fun ensureExecutable(file: File): Boolean {
        return file.exists() && (file.setExecutable(true, false) || file.canExecute())
    }

    private fun ensureReadable(file: File): Boolean {
        return file.exists() && (file.setReadable(true, false) || file.canRead())
    }
}
