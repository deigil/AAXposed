package io.github.nitsuya.aa.display.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.IShellManager

class ShellManagerService: Service() {
    companion object {
        private const val TAG = "AADisplay_ShellManagerService"
    }

    private lateinit var config: SharedPreferences
    private val stub: IShellManager.Stub = object: IShellManager.Stub(){
        override fun createVirtualDisplayBefore(): Boolean = execConfigShell(
            "createVirtualDisplayBefore",
            AADisplayConfig.CreateVirtualDisplayBefore.get(config)
        )

        override fun destroyVirtualDisplayAfter(): Boolean = execConfigShell(
            "destroyVirtualDisplayAfter",
            AADisplayConfig.DestroyVirtualDisplayAfter.get(config)
        )

        private fun execConfigShell(phase: String, commands: Array<String>): Boolean {
            if (commands.isEmpty()) {
                Log.i(TAG, "shell[$phase] skipped: no configured commands")
                return true
            }

            return try {
                val shell = Shell.getShell()
                if (!shell.isRoot) {
                    Log.e(TAG, "shell[$phase] failed: root shell unavailable")
                    return false
                }
                val result = shell.newJob().add(*commands).exec()
                Log.i(
                    TAG,
                    "shell[$phase]: success=${result.isSuccess}, code=${result.code}, " +
                        "commands=${commands.joinToString(" | ")}, " +
                        "out=${result.out.joinToString("\\n").take(512)}, " +
                        "err=${result.err.joinToString("\\n").take(512)}"
                )
                result.isSuccess
            } catch (e: Throwable) {
                Log.e(TAG, "shell[$phase] threw", e)
                false
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        config = SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        Log.i(TAG, "onCreate")
    }
    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: $intent")
        return stub
    }
}
