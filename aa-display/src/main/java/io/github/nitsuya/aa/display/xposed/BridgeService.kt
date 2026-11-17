package io.github.nitsuya.aa.display.xposed

import android.content.pm.IPackageManager
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.BuildConfig

object BridgeService {

    private const val TAG = "AADisplay_Bridge"

    /**
     * Only AADisplay's own uid may fetch the [CoreManagerService] binder.
     *
     * Consequence, and it is load-bearing: the Coolwalk geometry hooks run *inside* gearhead
     * (`:projection` / `:car`), i.e. under gearhead's uid, so every rail call they make is rejected
     * here — field logs show `Someone else trying to get my binder?` for each gearhead attempt and
     * `Binder acquired` only in AADisplay's own process. The rejection does not throw, and
     * `CoreManager.getService()` only catches `RemoteException`, so those calls return null / false
     * silently: `CoolwalkRailStore` in system_server never gets a server snapshot, and since
     * `CoolwalkRailStore.write` only runs there, the Settings.Global mirror stays empty too. `:car`'s
     * `fullHint`, `CoolwalkDrawingSpecWiden` and `DisplayProfileSettle` therefore each see only their
     * own process's guess of the HU width.
     *
     * That is exactly why `:car` could not learn the head-unit width: it has no HU-sized
     * VirtualDisplay of its own (the HU-sized `LayoutDisplay` lives and dies in `:projection`), and
     * this gate blocks the only channel that would have carried `:projection`'s measurement across.
     * So `:car` was left with the content slot (HU − rail) and the right edge went black.
     * [io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkHuScreenParamsHook] fixes that
     * `:car`-locally by reading gearhead's own negotiated `DisplayParams`, which needs no IPC.
     *
     * The gate itself stays as is. Widening it means handing gearhead narrow int-only rail actions
     * (never the full [ICoreManager] binder, which can start activities and inject touch events) —
     * a security-boundary change, deliberately kept separate now that a local source of truth
     * removes the need for it. `CoreManager.getService()` backs off after a failure so gearhead
     * no longer re-transacts to PMS on every geometry decision.
     */
    private var appUid = 0

    fun register(pms: IPackageManager) {
        log(TAG, "Initialize AADisplayService - Version ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        appUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pms.getPackageUid(BuildConfig.APPLICATION_ID, 0L, 0)
        } else {
            pms.getPackageUid(BuildConfig.APPLICATION_ID, 0, 0)
        }
        log(TAG, "Client uid: $appUid")
        log(TAG, "Service uid: ${Process.myUid()}")
        log(TAG, "Initialize service proxy")
        pms.javaClass.findMethod(true) {
            name == "onTransact"
        }.hookBefore { param ->
            val code = param.args[0] as Int
            val data = param.args[1] as Parcel
            val reply = param.args[2] as Parcel?
            if (myTransact(code, data, reply)) param.result = true
        }
    }

    const val TRANSACTION = ('A'.code shl 24) or ('A'.code shl 16) or ('D'.code shl 8) or 'D'.code
    const val DESCRIPTOR = "android.content.pm.IPackageManager"
    const val ACTION_GET_BINDER = 1

    private fun myTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
        if (code == TRANSACTION) {
            if (Binder.getCallingUid() == appUid) {
                log(TAG, "Transaction from client")
                runCatching {
                    data.enforceInterface(DESCRIPTOR)
                    when (data.readInt()) {
                        ACTION_GET_BINDER -> {
                            reply?.writeNoException()
                            reply?.writeStrongBinder(CoreManagerService.instance)
                            return true
                        }
                        else -> log(TAG, "Unknown action")
                    }
                }.onFailure {
                    log(TAG, "Transaction error", it)
                }
            } else {
                log(TAG, "Someone else trying to get my binder?")
            }
            data.setDataPosition(0)
            reply?.setDataPosition(0)
        }
        return false
    }
}
