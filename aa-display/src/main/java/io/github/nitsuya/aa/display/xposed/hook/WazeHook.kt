package io.github.nitsuya.aa.display.xposed.hook

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.WazeOnAaManager
import io.github.nitsuya.aa.display.xposed.HookPreferences

object WazeHook : BaseHook() {
    override val tagName: String = "AAD_WazeHook"

    private const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
    private const val CAR_CONNECTION_STATE = "CarConnectionState"

    override fun init(target: HookTarget) {
        hookContentResolverQueries()
        hookContentResolverCalls()
        hookContentProviderClientQueries()
        hookContentProviderClientCalls()
    }

    private fun hookContentResolverQueries() {
        hookMethodsByName(ContentResolver::class.java, "query") { param ->
            val uri = extractUri(param.args) ?: return@hookMethodsByName
            if (!shouldIntercept(uri, param.thisObject as? ContentResolver)) return@hookMethodsByName
            param.result = buildNotConnectedCursor(extractProjection(param.args))
        }
    }

    private fun hookContentResolverCalls() {
        hookMethodsByName(ContentResolver::class.java, "call") { param ->
            val authority = extractCallAuthority(param.args) ?: return@hookMethodsByName
            if (authority != CAR_CONNECTION_AUTHORITY) return@hookMethodsByName
            if (!isDisableEnabled(param.thisObject as? ContentResolver)) return@hookMethodsByName
            param.result = Bundle().apply {
                putInt(CAR_CONNECTION_STATE, 0)
            }
        }
    }

    private fun hookContentProviderClientQueries() {
        hookMethodsByName(ContentProviderClient::class.java, "query") { param ->
            val uri = extractUri(param.args) ?: return@hookMethodsByName
            if (!shouldIntercept(uri, null)) return@hookMethodsByName
            param.result = buildNotConnectedCursor(extractProjection(param.args))
        }
    }

    private fun hookContentProviderClientCalls() {
        hookMethodsByName(ContentProviderClient::class.java, "call") { param ->
            val authority = extractCallAuthority(param.args) ?: return@hookMethodsByName
            if (authority != CAR_CONNECTION_AUTHORITY) return@hookMethodsByName
            if (!isDisableEnabled(null)) return@hookMethodsByName
            param.result = Bundle().apply {
                putInt(CAR_CONNECTION_STATE, 0)
            }
        }
    }

    private fun hookMethodsByName(
        targetClass: Class<*>,
        methodName: String,
        beforeHookedMethod: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        targetClass.declaredMethods
            .filter { method -> method.name == methodName }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        beforeHookedMethod(param)
                    }
                })
            }
    }

    private fun shouldIntercept(
        uri: Uri,
        contentResolver: ContentResolver?
    ): Boolean {
        if (uri.authority != CAR_CONNECTION_AUTHORITY) return false
        return isDisableEnabled(contentResolver)
    }

    private fun isDisableEnabled(
        contentResolver: ContentResolver?
    ): Boolean {
        if (contentResolver != null) {
            val globalValue = runCatching {
                Settings.Global.getInt(
                    contentResolver,
                    WazeOnAaManager.GLOBAL_DISABLE_WAZE_ON_AA_KEY,
                    -1
                )
            }.getOrNull()

            if (globalValue != null && globalValue >= 0) {
                return globalValue == 1
            }
        }

        // 现代 API 的远端配置是实时的，不需要（也没有）reload。
        return AADisplayConfig.DisableWazeOnAa.get(HookPreferences.latest())
    }

    private fun extractUri(args: Array<Any?>): Uri? {
        return args.firstOrNull { it is Uri } as? Uri
    }

    private fun extractCallAuthority(args: Array<Any?>): String? {
        if (args.isEmpty()) return null
        val firstArg = args[0]
        return when (firstArg) {
            is Uri -> firstArg.authority
            is String -> firstArg
            else -> (args.firstOrNull { it is Uri } as? Uri)?.authority
        }
    }

    private fun extractProjection(args: Array<Any?>): Array<String>? {
        for (arg in args) {
            if (arg is Array<*> && arg.all { it == null || it is String }) {
                @Suppress("UNCHECKED_CAST")
                return arg as? Array<String>
            }
        }
        return null
    }

    private fun buildNotConnectedCursor(projection: Array<String>?): Cursor {
        val columns = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(CAR_CONNECTION_STATE)
        val row = Array<Any?>(columns.size) { index ->
            if (columns[index] == CAR_CONNECTION_STATE) 0 else null
        }
        return MatrixCursor(columns, 1).apply {
            addRow(row)
        }
    }
}
