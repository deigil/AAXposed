package io.github.nitsuya.aa.display

import android.content.Context
import android.os.Process
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.RemoteConfigMirror
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.CoreManagerService


val IsSystemEnv by lazy {
    Process.myUid() == 1000
}
val CoreApi by lazy {
    if(!IsSystemEnv) CoreManager
    else CoreManagerService.instance!!
}
lateinit var App : Application
class Application: android.app.Application() {
    init {
        App = this
        tryOrNull {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 旧框架路径：hook 侧用 XSharedPreferences 读这份本地 xml，需要 others 可读。
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        // 现代 API 路径：hook 侧读框架守护进程里的远端配置，本地改动在这里镜像过去。
        RemoteConfigMirror.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }
}
